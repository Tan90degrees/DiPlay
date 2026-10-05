package com.shihab.diplay.legacy

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21], application = Application::class)
class LegacyStartupTest {
    @Test fun launcherCanStartAndStopWithoutUsbOrAuthenticationAssets() {
        val activity = Robolectric.buildActivity(WiredActivity::class.java).create().start().resume()
        assertNotNull(activity.get().window.decorView)
        activity.pause().stop().destroy()
    }
    @Test fun sourceBuildFailsClosedWithoutAuthenticationMaterial() {
        try { LegacyIdentity(RuntimeEnvironment.getApplication()).mfi(); fail("Missing authentication was accepted") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("认证")) }
    }
    @Test fun accessoryIdentitySurvivesRestartWithoutApi21Storage() {
        val context = RuntimeEnvironment.getApplication()
        val first = LegacyIdentity(context).identity()
        val second = LegacyIdentity(context).identity()
        assertArrayEquals(first.privateKey, second.privateKey)
        assertArrayEquals(first.publicKey, second.publicKey)
        assertEquals(first.pairingId, second.pairingId)
    }
    @Test fun unpairedLowercaseHostIdsAreNormalizedAndPersistedButAcceptedRecordsKeepTheirIds() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("wired-identity", 0)
        val lower = "aabbccdd-0123-4567-89ab-0123456789ab"
        prefs.edit().clear().putString("host", lower).putString("buid", lower).commit()
        val identity = LegacyIdentity(context)
        val upper = lower.uppercase(java.util.Locale.US)
        assertEquals(upper, identity.uuid("host")); assertEquals(upper, identity.uuid("buid"))
        assertEquals(upper, prefs.getString("host", null))
        prefs.edit().putString("host", lower).putString("buid", lower)
            .putString("lockdown-device-key", "accepted-record-marker").commit()
        assertEquals(lower, identity.uuid("host")); assertEquals(lower, identity.uuid("buid"))
        assertEquals(lower, prefs.getString("host", null))
        prefs.edit().clear().commit()
    }
}
