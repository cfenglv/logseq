package com.logseq.app

import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeBottomSheetLifecycleInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var plugin: NativeBottomSheetPlugin

    @Before
    fun launchActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val activityRef = AtomicReference<MainActivity>()
        scenario.onActivity { activityRef.set(it) }
        activity = activityRef.get()
        plugin = activity.bridge
            .getPlugin("NativeBottomSheetPlugin")
            .instance as NativeBottomSheetPlugin
    }

    @After
    fun closeActivity() {
        scenario.close()
    }

    @Test
    fun presentDoesNotReenterWhileDismissalAwaitsContentReady() {
        evaluatePlugin("present", "{ defaultHeight: 400 }")
        awaitCondition { privateField<Any?>("dialog") != null }

        evaluatePlugin("dismiss")
        awaitCondition {
            privateField<Boolean>("awaitingContentReady") &&
                privateField<Any?>("dialog") == null
        }

        evaluatePlugin("present", "{ defaultHeight: 400 }")
        Thread.sleep(250)

        assertTrue(privateField("awaitingContentReady"))
        assertNull(
            "a second sheet must not be created while the prior WebView is restoring",
            privateField<Any?>("dialog")
        )

        evaluatePlugin("contentReady")
        awaitCondition { !privateField<Boolean>("awaitingContentReady") }

        evaluatePlugin("present", "{ defaultHeight: 400 }")
        awaitCondition { privateField<Any?>("dialog") != null }
        assertNotNull(privateField<Any?>("dialog"))

        evaluatePlugin("dismiss")
        awaitCondition { privateField<Boolean>("awaitingContentReady") }
        evaluatePlugin("contentReady")
    }

    @Test
    fun visibleSnapshotInterceptsTouchesAndClearedSnapshotRestoresDispatch() {
        evaluatePlugin("present", "{ defaultHeight: 400 }")
        val overlay = awaitOverlay(visible = true)

        assertTrue(
            "the visible WebView snapshot must consume touches",
            dispatchDown(overlay)
        )

        evaluatePlugin("dismiss")
        awaitCondition { privateField<Boolean>("awaitingContentReady") }
        evaluatePlugin("contentReady")
        awaitCondition { overlay.visibility == View.GONE }

        assertFalse(
            "touch interception must stop after the snapshot is cleared",
            dispatchDown(overlay)
        )
    }

    @Test
    fun dismissCompletesWhenCljsPopupDataWasAlreadyEmpty() {
        // Calling the native plugin directly intentionally leaves the CLJS popup-data
        // atom empty. The dismissing state listener must still acknowledge contentReady.
        evaluatePlugin("present", "{ defaultHeight: 400 }")
        awaitCondition { privateField<Any?>("dialog") != null }

        evaluatePlugin("dismiss")
        awaitCondition { privateField<Boolean>("awaitingContentReady") }

        val promptDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        while (
            System.nanoTime() < promptDeadline &&
            privateField<Boolean>("awaitingContentReady")
        ) {
            Thread.sleep(25)
        }

        assertFalse(
            "CLJS must acknowledge native dismissal before the 2-second fallback",
            privateField<Boolean>("awaitingContentReady")
        )
    }

    private fun evaluatePlugin(method: String, args: String = "{}") {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        activity.runOnUiThread {
            activity.bridge.webView.evaluateJavascript(
                """
                (() => {
                  const plugin = window.Capacitor &&
                    window.Capacitor.Plugins &&
                    window.Capacitor.Plugins.NativeBottomSheetPlugin;
                  if (!plugin) throw new Error("NativeBottomSheetPlugin unavailable");
                  plugin.$method($args);
                  return "invoked";
                })()
                """.trimIndent()
            ) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        assertTrue("JavaScript plugin call timed out", latch.await(10, TimeUnit.SECONDS))
        assertTrue(
            "JavaScript plugin call failed: ${result.get()}",
            result.get()?.contains("invoked") == true
        )
    }

    private fun awaitOverlay(visible: Boolean): View {
        val overlayRef = AtomicReference<View>()
        awaitCondition {
            val latch = CountDownLatch(1)
            activity.runOnUiThread {
                overlayRef.set(
                    activity.findViewById(R.id.webview_overlay_container)
                )
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            val overlay = overlayRef.get()
            overlay != null && (overlay.visibility == View.VISIBLE) == visible
        }
        return overlayRef.get()
    }

    private fun dispatchDown(view: View): Boolean {
        val consumed = AtomicBoolean()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val event = MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                10f,
                10f,
                0
            )
            consumed.set(view.dispatchTouchEvent(event))
            event.recycle()
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        return consumed.get()
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        assertTrue("condition was not reached before timeout", predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(name: String): T {
        val field = NativeBottomSheetPlugin::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(plugin) as T
    }
}
