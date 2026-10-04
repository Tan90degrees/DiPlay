package com.shihab.diplay.legacy

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21], application = Application::class)
class RootlessAndroidTest {
    @Test fun oldRootPreferencesDoNotSelectRootOrAnExternalHelper() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("network-compatibility", Context.MODE_PRIVATE).edit()
            .putBoolean("root-ipv6", true).putBoolean("adb-root-ipv6", true).commit()
        val service = Robolectric.buildService(WiredVpnService::class.java).create()
        try {
            val address = service.get().localAddress()
            assertArrayEquals(if (Build.VERSION.SDK_INT < 21) RootlessIp.host4 else RootlessIp.host6, address.address)
        } finally { service.destroy() }
    }
    @Test fun cancelledRootlessProbeDoesNotReadOrWriteItsDescriptor() {
        val pipe = ParcelFileDescriptor.createPipe()
        val messages = mutableListOf<String>()
        try {
            RootlessNetworkProbe.run(pipe[0], AtomicBoolean(true), messages::add)
            assertTrue(messages.isEmpty())
        } finally { pipe.forEach { it.close() } }
    }
}
