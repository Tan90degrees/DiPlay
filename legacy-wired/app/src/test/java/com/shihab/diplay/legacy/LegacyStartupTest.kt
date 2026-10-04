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
}
