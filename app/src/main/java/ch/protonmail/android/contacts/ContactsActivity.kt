/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.contacts

import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProviders
import androidx.viewpager.widget.ViewPager
import ch.protonmail.android.R
import ch.protonmail.android.activities.BaseConnectivityActivity
import ch.protonmail.android.contacts.details.edit.EditContactDetailsActivity
import ch.protonmail.android.contacts.groups.edit.ContactGroupEditCreateActivity
import ch.protonmail.android.contacts.groups.list.ContactGroupsFragment
import ch.protonmail.android.contacts.groups.list.ContactsFragmentsPagerAdapter
import ch.protonmail.android.contacts.list.ContactsListFragment
import ch.protonmail.android.contacts.list.search.OnSearchClose
import ch.protonmail.android.contacts.list.search.SearchExpandListener
import ch.protonmail.android.contacts.list.search.SearchViewQueryListener
import ch.protonmail.android.core.Constants
import ch.protonmail.android.events.AttachmentFailedEvent
import ch.protonmail.android.events.ConnectivityEvent
import ch.protonmail.android.events.ContactsFetchedEvent
import ch.protonmail.android.events.LogoutEvent
import ch.protonmail.android.events.Status
import ch.protonmail.android.events.user.MailSettingsEvent
import ch.protonmail.android.jobs.FetchContactsDataJob
import ch.protonmail.android.permissions.PermissionHelper
import ch.protonmail.android.utils.AppUtil
import ch.protonmail.android.utils.NetworkUtil
import ch.protonmail.android.utils.extensions.showToast
import ch.protonmail.android.utils.moveToLogin
import ch.protonmail.android.worker.FetchContactsEmailsWorker
import com.birbit.android.jobqueue.JobManager
import com.github.clans.fab.FloatingActionButton
import com.squareup.otto.Subscribe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_contacts_v2.*
import javax.inject.Inject
import kotlin.time.seconds

// region constants
const val REQUEST_CODE_CONTACT_DETAILS = 1
const val REQUEST_CODE_NEW_CONTACT = 2
const val REQUEST_CODE_CONVERT_CONTACT = 3
// endregion

@AndroidEntryPoint
class ContactsActivity : BaseConnectivityActivity(),
    IContactsListFragmentListener,
    ContactsActivityContract {

    override fun dataUpdated(position: Int, count: Int) {
        pagerAdapter.update(position, count)
        tabLayout.setupWithViewPager(viewPager, true)
    }

    override val jobManager: JobManager get() = mJobManager

    @Inject
    lateinit var enqueueFetchContactsEmails: FetchContactsEmailsWorker.Enqueuer

    private val contactsConnectivityRetryListener = ConnectivityRetryListener()

    private var alreadyCheckedPermission = false
    @Inject
    lateinit var contactsViewModelFactory: ContactsViewModelFactory
    private lateinit var contactsViewModel: ContactsViewModel

    lateinit var pagerAdapter: ContactsFragmentsPagerAdapter

    override fun getLayoutId() = R.layout.activity_contacts_v2

    private val contactsPermissionHelper by lazy {
        PermissionHelper.newInstance(
            Constants.PermissionType.CONTACTS,
            this,
            ContactsPermissionHelperCallbacks(),
            true
        )
    }

    private inner class ContactsPermissionHelperCallbacks : PermissionHelper.PermissionCallback {
        override fun onPermissionConfirmed(type: Constants.PermissionType) {
            pagerAdapter.onContactPermissionChange(supportFragmentManager, true)
        }

        override fun onPermissionDenied(type: Constants.PermissionType) {
            pagerAdapter.onContactPermissionChange(supportFragmentManager, false)
        }

        override fun onHasPermission(type: Constants.PermissionType) = onPermissionConfirmed(type)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.contacts)
        }

        contactsViewModel =
                ViewModelProviders.of(this, contactsViewModelFactory)
                    .get(ContactsViewModel::class.java)
        pagerAdapter = ContactsFragmentsPagerAdapter(this, supportFragmentManager)
        viewPager.adapter = pagerAdapter
        viewPager?.addOnPageChangeListener(ViewPagerOnPageSelected(this@ContactsActivity::onPageSelected))
        tabLayout.setupWithViewPager(viewPager)

        addContactItem.setOnClickListener {
            startActivityForResult(
                EditContactDetailsActivity.startNewContactActivity(this),
                REQUEST_CODE_NEW_CONTACT
            )
            addFab.close(false)
        }

        addContactGroupItem.setOnClickListener {
            if (!contactsViewModel.isPaidUser()) {
                showToast(R.string.paid_plan_needed)
                return@setOnClickListener
            }
            val intent =
                AppUtil.decorInAppIntent(Intent(this, ContactGroupEditCreateActivity::class.java))
            startActivity(intent)
            addFab.close(false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.contacts_menu, menu)
        val searchItem = menu?.findItem(R.id.action_search)
        searchItem?.configureSearch()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        contactsPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()
        if (!mNetworkUtil.isConnected()) {
            showNoConnSnack(callback = this)
        }
    }

    override fun onStart() {
        super.onStart()
        mApp.bus.register(this)
        if (!alreadyCheckedPermission) {
            requestContactsPermission()
        }
    }

    override fun requestContactsPermission() {
        contactsPermissionHelper.checkPermission()
        alreadyCheckedPermission = true
    }

    override fun onStop() {
        mApp.bus.unregister(this)
        alreadyCheckedPermission = false
        super.onStop()
    }

    private inner class ConnectivityRetryListener : RetryListener() {
        override fun onClick(v: View) {
            super.onClick(v)
            mNetworkUtil.setCurrentlyHasConnectivity(true)
            mCheckForConnectivitySnack = NetworkUtil.setCheckingConnectionSnackLayout(
                layout_no_connectivity_info,
                this@ContactsActivity
            )
            mCheckForConnectivitySnack!!.show()

            // Dimitar: manually check if we have network connectivity and initiate DOH if we do
            if (mNetworkUtil.isConnectedAndHasConnectivity()) {
                // TODO: DoH
//                mJobManager.addJobInBackground(DnsOverHttpsJob(this@ContactsActivity, this@ContactsActivity))
            }
        }
    }

    @Subscribe
    fun onConnectivityEvent(event: ConnectivityEvent) {
        if (!event.hasConnection()) {
            showNoConnSnack(contactsConnectivityRetryListener, view = layout_no_connectivity_info, callback = this)
        } else {
            mPingHasConnection = true
            hideNoConnSnack()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.action_sync -> {
                progressLayoutView!!.visibility = View.VISIBLE
                refresh()
                return true
            }
            else -> {
                val fragment = supportFragmentManager.fragments[0] as ContactsListFragment
                if (fragment.isAdded) {
                    fragment.optionsItemSelected(item)
                }
                return true
            }
        }
    }

    private fun refresh() {
        mJobManager.addJobInBackground(FetchContactsDataJob())
        enqueueFetchContactsEmails(2.seconds)
    }

    private fun MenuItem.configureSearch() {
        val searchView = actionView as SearchView
        val searchListeners = pagerAdapter.getSearchListeners(supportFragmentManager)
        setOnActionExpandListener(SearchExpandListener(searchView, searchListeners))
        val searchTextView =
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
        try {
            val mCursorDrawableRes = TextView::class.java.getDeclaredField("mCursorDrawableRes")
            mCursorDrawableRes.isAccessible = true
            mCursorDrawableRes.set(searchTextView, R.drawable.cursor)
        } catch (ignored: Exception) {
            // NOOP
        }
        searchView.maxWidth = Integer.MAX_VALUE
        searchView.queryHint = getString(R.string.search_contacts)
        searchView.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        searchView.setOnQueryTextListener(SearchViewQueryListener(searchView, searchListeners))
        val closeButton = searchView.findViewById<ImageView>(R.id.search_close_btn)
        closeButton.setOnClickListener(OnSearchClose(searchView, searchListeners))
    }

    @Subscribe
    @Suppress("UNUSED_PARAMETER")
    fun onMailSettingsEvent(event: MailSettingsEvent) {
        loadMailSettings()
    }

    @Subscribe
    @Suppress("unused", "UNUSED_PARAMETER")
    fun onLogoutEvent(event: LogoutEvent) {
        moveToLogin()
    }

    @Subscribe
    @Suppress("unused")
    fun onAttachmentFailedEvent(event: AttachmentFailedEvent) {
        showToast(getString(R.string.attachment_failed, event.messageSubject, event.attachmentName))
    }

    @Subscribe
    @Suppress("unused")
    fun onContactsFetchedEvent(event: ContactsFetchedEvent) {
        progressLayoutView!!.visibility = View.GONE
        val toastTextId =
            if (event.status == Status.SUCCESS) R.string.fetching_contacts_success
            else R.string.fetching_contacts_failure
        showToast(toastTextId, Toast.LENGTH_SHORT)
    }

    override fun onBackPressed() {
        if (addFab.isOpened) {
            addFab.close(true)
        } else {
            super.onBackPressed()
        }
    }

    private val contactsListFragment get() = supportFragmentManager.fragments[0] as ContactsListFragment
    private val contactGroupsFragment get() = supportFragmentManager.fragments[1] as ContactGroupsFragment

    private fun onPageSelected(position: Int) {
        addFab.visibility = ViewGroup.VISIBLE
        val recyclerViewBottomPadding = {
            val mainFab = addFab.children.first { it is FloatingActionButton }
            mainFab.height + (window.decorView.height - addFab.bottom) * 2
        }
        when (position) {
            0 -> {
                window.decorView.doOnPreDraw {
                    contactsListFragment.updateRecyclerViewBottomPadding(recyclerViewBottomPadding())
                }
                contactGroupsFragment.apply {
                    if (isAdded && actionMode != null)
                        onDestroyActionMode(null)
                }
            }
            1 -> {
                window.decorView.doOnPreDraw {
                    contactGroupsFragment.updateRecyclerViewBottomPadding(recyclerViewBottomPadding())
                }
                contactsListFragment.apply {
                    if (isAdded && actionMode != null)
                        onDestroyActionMode(null)
                }
            }
        }
    }

    override fun setTitle(title: String) {
        this.title = title
    }

    override fun selectPage(position: Int) = onPageSelected(position)

    override fun doRequestContactsPermission() = requestContactsPermission()

    override fun doStartActionMode(callback: ActionMode.Callback): ActionMode? = startActionMode(callback)

    override fun doStartActivityForResult(intent: Intent, requestCode: Int) =
        startActivityForResult(intent, requestCode)

    override fun registerObject(registerObject: Any) = mApp.bus.register(registerObject)

    override fun unregisterObject(unregisterObject: Any) = mApp.bus.unregister(unregisterObject)
}

class ViewPagerOnPageSelected(private val pageSelected: (Int) -> Unit = {}) : ViewPager.OnPageChangeListener {

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        pageSelected(position)
    }

    override fun onPageScrollStateChanged(state: Int) {}
}

interface ContactsActivityContract {
    fun requestContactsPermission()
}
