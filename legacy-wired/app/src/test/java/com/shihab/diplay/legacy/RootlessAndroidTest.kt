package com.shihab.diplay.legacy

import android.app.Application
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21, 22], application = Application::class, shadows = [RootlessVpnBuilderShadow::class])
class RootlessAndroidTest {
    @Test fun oldRootPreferencesDoNotSelectRootOrAnExternalHelper() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("network-compatibility", Context.MODE_PRIVATE).edit()
            .putBoolean("root-ipv6", true).putBoolean("adb-root-ipv6", true).commit()
        val service = Robolectric.buildService(WiredVpnService::class.java).create()
        try {
            val address = service.get().localAddress()
            assertArrayEquals(RootlessIp.host4, address.address)
        } finally { service.destroy() }
    }
    @Test fun tunnelUsesOnlyTheScopedIpv4RouteIncludingOnAndroid51() {
        val service = Robolectric.buildService(WiredVpnService::class.java).create()
        val pipe = ParcelFileDescriptor.createPipe()
        RootlessVpnBuilderShadow.addresses.clear(); RootlessVpnBuilderShadow.routes.clear()
        RootlessVpnBuilderShadow.allowed.clear(); RootlessVpnBuilderShadow.mtu = 0
        RootlessVpnBuilderShadow.descriptor = pipe[0]
        try {
            val tun = ReflectionHelpers.callInstanceMethod<ParcelFileDescriptor>(service.get(), "tunnel",
                ClassParameter.from(String::class.java, "rootless regression"))
            assertSame(pipe[0], tun)
            assertEquals(listOf("198.18.0.2" to 24), RootlessVpnBuilderShadow.addresses)
            assertEquals(listOf("198.18.0.0" to 24), RootlessVpnBuilderShadow.routes)
            assertEquals(1260, RootlessVpnBuilderShadow.mtu)
            assertEquals(if (Build.VERSION.SDK_INT >= 21) listOf(service.get().packageName) else emptyList<String>(),
                RootlessVpnBuilderShadow.allowed)
        } finally {
            RootlessVpnBuilderShadow.descriptor = null
            pipe.forEach { it.close() }; service.destroy()
        }
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

/** Capture the actual VPN configuration without emulating kernel routes or native TUN IO. */
@Implements(VpnService.Builder::class)
class RootlessVpnBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder
    companion object {
        val addresses = mutableListOf<Pair<String, Int>>()
        val routes = mutableListOf<Pair<String, Int>>()
        val allowed = mutableListOf<String>()
        var mtu = 0
        var descriptor: ParcelFileDescriptor? = null
    }
    @Implementation protected fun setMtu(value: Int): VpnService.Builder { mtu = value; return builder }
    @Implementation protected fun addAddress(address: String, prefix: Int): VpnService.Builder {
        addresses += address to prefix; return builder
    }
    @Implementation protected fun addRoute(address: String, prefix: Int): VpnService.Builder {
        routes += address to prefix; return builder
    }
    @Implementation(minSdk = 21) protected fun addAllowedApplication(packageName: String): VpnService.Builder {
        allowed += packageName; return builder
    }
    @Implementation protected fun establish(): ParcelFileDescriptor? = descriptor
}
