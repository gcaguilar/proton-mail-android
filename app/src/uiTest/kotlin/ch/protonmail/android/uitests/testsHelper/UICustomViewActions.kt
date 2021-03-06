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
package ch.protonmail.android.uitests.testsHelper

import android.view.View
import android.widget.NumberPicker
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.AmbiguousViewMatcherException
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.Espresso
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.PositionableRecyclerViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.protonmail.android.R
import junit.framework.AssertionFailedError
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.Matcher
import org.jetbrains.annotations.Contract
import kotlin.test.assertFalse

object UICustomViewActions {

    private const val TIMEOUT_10S = 10_000L
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    fun waitUntilViewAppears(interaction: ViewInteraction, timeout: Long = TIMEOUT_10S): ViewInteraction {
        try {
            ProtonWatcher.setTimeout(timeout)
            ProtonWatcher.waitForCondition(object : ProtonWatcher.Condition() {
                var errorMessage = ""

                override fun getDescription() = "UICustomViewActions.waitUntilViewAppears $errorMessage"

                override fun checkCondition(): Boolean {
                    return try {
                        interaction.check(matches(isDisplayed()))
                        true
                    } catch (e: PerformException) {
                        errorMessage = "${e.viewDescription}, Action: ${e.actionDescription}"
                        false
                    } catch (e: NoMatchingViewException) {
                        errorMessage = e.viewMatcherDescription
                        false
                    } catch (e: NoMatchingRootException) {
                        false
                    } catch (e: AppNotIdleException) {
                        false
                    } catch (e: AmbiguousViewMatcherException) {
                        false
                    } catch (e: AssertionFailedError) {
                        false
                    }
                }
            })
        } catch (e: Throwable) {
            if (ProtonWatcher.status == ProtonWatcher.TIMEOUT) {
                throw e
            }
        }
        return interaction
    }

    fun waitUntilViewIsGone(interaction: ViewInteraction, timeout: Long = TIMEOUT_10S): ViewInteraction {
        try {
            ProtonWatcher.setTimeout(timeout)
            ProtonWatcher.waitForCondition(object : ProtonWatcher.Condition() {
                var errorMessage = ""

                override fun getDescription() = "waitForElement - $errorMessage"

                override fun checkCondition() = try {
                    interaction.check(doesNotExist())
                    true
                } catch (e: PerformException) {
                    errorMessage = "${e.viewDescription}, Action: ${e.actionDescription}"
                    false
                } catch (e: NoMatchingViewException) {
                    errorMessage = e.viewMatcherDescription
                    false
                } catch (e: NoMatchingRootException) {
                    false
                } catch (e: AppNotIdleException) {
                    false
                } catch (e: AmbiguousViewMatcherException) {
                    false
                } catch (e: AssertionFailedError) {
                    false
                }
            })
        } catch (e: Throwable) {
            if (ProtonWatcher.status == ProtonWatcher.TIMEOUT) {
                throw e
            }
        }
        return interaction
    }

    fun waitUntilRecyclerViewPopulated(@IdRes id: Int, timeout: Long = TIMEOUT_10S) {
        ProtonWatcher.setTimeout(timeout)
        ProtonWatcher.waitForCondition(object : ProtonWatcher.Condition() {
            var errorMessage = ""

            override fun getDescription() =
                "RecyclerView: ${targetContext.resources.getResourceName(id)} was not populated with items"

            override fun checkCondition() = try {
                val rv = ActivityProvider.currentActivity!!.findViewById<RecyclerView>(id)
                waitUntilLoaded{ rv }
                rv.adapter!!.itemCount > 0
            } catch (e: Exception) {
                errorMessage = e.message.toString()
                false
            }
        })
    }

    /**
     * Stop the test until RecyclerView's data gets loaded.
     * Passed [recyclerProvider] will be activated in UI thread, allowing you to retrieve the View.
     * Workaround for https://issuetracker.google.com/issues/123653014.
     */
    inline fun waitUntilLoaded(crossinline recyclerProvider: () -> RecyclerView) {
        Espresso.onIdle()
        lateinit var recycler: RecyclerView

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            recycler = recyclerProvider()
        }

        while (recycler.hasPendingAdapterUpdates()) {
            Thread.sleep(10)
        }
    }

    @Contract(value = "_ -> new", pure = true)
    fun setValueInNumberPicker(num: Int): ViewAction {
        return object : ViewAction {
            override fun perform(uiController: UiController, view: View) {
                (view as NumberPicker).value = num
            }

            override fun getDescription(): String = "Set the passed number into the NumberPicker"

            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
        }
    }

    fun saveMessageSubject(position: Int, saveSubject: (String, String) -> Unit) = object : ViewAction {
        override fun getConstraints() = isAssignableFrom(RecyclerView::class.java)

        override fun getDescription() = "Fetches the message subject at position $position"

        override fun perform(uiController: UiController, view: View) {
            val recyclerView = view as RecyclerView
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            val messageSubject = layoutManager.getChildAt(position)
                ?.findViewById<TextView>(R.id.messageTitleTextView)!!.text.toString()
            val messageDate = layoutManager.getChildAt(position)
                ?.findViewById<TextView>(R.id.messageDateTextView)!!.text.toString()
            saveSubject.invoke(messageSubject, messageDate)
        }
    }

    fun checkItemDoesNotExist(subject: String, date: String): PositionableRecyclerViewAction =
        CheckItemDoesNotExist(subject, date)

    class CheckItemDoesNotExist(
        private val subject: String,
        private val date: String
    ) : PositionableRecyclerViewAction {

        override fun atPosition(position: Int): PositionableRecyclerViewAction =
            checkItemDoesNotExist(subject, date)

        override fun getDescription(): String = "Checking if message with subject exists in the list."

        override fun getConstraints(): Matcher<View> = allOf(isAssignableFrom(RecyclerView::class.java), isDisplayed())

        override fun perform(uiController: UiController?, view: View?) {
            var isMatches = true
            var messageSubject = ""
            var messageDate = ""
            val recyclerView = view as RecyclerView
            for (i in 0..recyclerView.adapter!!.itemCount) {
                val item = recyclerView.getChildAt(i)
                if (item != null) {
                    messageSubject = item.findViewById<TextView>(R.id.messageTitleTextView).text.toString()
                    messageDate = item.findViewById<TextView>(R.id.messageDateTextView).text.toString()
                    isMatches = messageSubject == subject && messageDate == date
                    if (isMatches) {
                        break
                    }
                }
            }
            assertFalse(isMatches, "RecyclerView should not contain item with subject: \"$subject\"")
        }
    }

    fun clickOnChildWithId(@IdRes id: Int): ViewAction {
        return object : ViewAction {
            override fun perform(uiController: UiController, view: View) {
                view.findViewById<View>(id).callOnClick()
            }

            override fun getDescription(): String = "Click child view with id."

            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        }
    }
}
