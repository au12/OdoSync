package cc.adamu.odosync.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.data.TripSummary
import cc.adamu.odosync.data.UploadOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripNotifierTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val store get() = TripStore.get(app)

    private fun shown(): Notification? = shadowOf(nm).getNotification(TripNotifier.ID)
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.get(app).update { putString("uploadUnit", "MILES"); putInt("offset", 0) }
        nm.cancelAll()
        val end = 1_700_000_000_000L
        // 20 km in 25 min, ending at 98 550 km.
        store.saveSummary(TripSummary(98_530, 98_550, end - 25 * 60_000, end, UploadOutcome.QUEUED))
    }

    @Test fun summaryShowsStatsAndPendingUpload() {
        TripNotifier.show(app)
        val n = shown()!!
        assertEquals("Trip complete · +12.4 mi", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val body = text(n)
        assertTrue(body, body.contains("25 min"))
        assertTrue(body, body.contains("avg 30 mph"))
        assertTrue(body, body.contains("odometer 61,236 mi"))
        assertTrue(body, body.contains("Uploading to LubeLogger"))
        // Dismissable, not ongoing.
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun uploadResultUpdatesTheVisibleSummary() {
        TripNotifier.show(app)
        assertTrue(store.setSummaryUpload(98_550, UploadOutcome.UPLOADED))
        TripNotifier.show(app, onlyIfShowing = true)
        assertTrue(text(shown()!!).contains("✓ Uploaded 61,236 mi to LubeLogger"))
    }

    @Test fun failureIsShownWithReason() {
        TripNotifier.show(app)
        store.setSummaryUpload(98_550, UploadOutcome.FAILED, "HTTP 401 (check the API key)")
        TripNotifier.show(app, onlyIfShowing = true)
        assertTrue(text(shown()!!).contains("Upload failed: HTTP 401 (check the API key)"))
    }

    @Test fun dismissedSummaryIsNotResurrectedByTheUploadWorker() {
        TripNotifier.show(app)
        nm.cancel(TripNotifier.ID) // user swiped it away
        store.setSummaryUpload(98_550, UploadOutcome.UPLOADED)
        TripNotifier.show(app, onlyIfShowing = true)
        assertNull(shown())
    }

    @Test fun olderUploadDoesNotOverwriteNewerTrip() {
        // An upload of an earlier reading (e.g. the 6-hourly safety net) isn't this trip's result.
        assertFalse(store.setSummaryUpload(98_540, UploadOutcome.UPLOADED))
        assertEquals(UploadOutcome.QUEUED, store.current.summary!!.upload)
        // A final result isn't overwritten by a later retry of something else either.
        store.setSummaryUpload(98_550, UploadOutcome.UPLOADED)
        assertFalse(store.setSummaryUpload(98_560, UploadOutcome.RETRYING))
    }

    @Test fun kilometres() {
        Settings.get(app).update { putString("uploadUnit", "KILOMETERS") }
        TripNotifier.show(app)
        assertEquals("Trip complete · +20 km", shown()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
