package com.shihab.diplay.legacy

import android.app.Application
import android.content.Context
import android.net.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 22], application = Application::class, shadows = [VpnConnectivityShadow::class])
class VpnNetworkBindingAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private fun properties(name: String, address: String = RootlessIp.HOST4, prefix: Int = 24) = LinkProperties().apply {
        // These setters/constructors were hidden on API 21; only test fixtures use reflection.
        ReflectionHelpers.callInstanceMethod<Any>(this, "setInterfaceName", ClassParameter.from(String::class.java, name))
        val link = ReflectionHelpers.callConstructor<LinkAddress>(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName(address)), ClassParameter.from(Integer.TYPE, prefix))
        ReflectionHelpers.callInstanceMethod<Any>(this, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, link))
        val destination = ReflectionHelpers.callConstructor<IpPrefix>(IpPrefix::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName(RootlessIp.PREFIX4)), ClassParameter.from(Integer.TYPE, prefix))
        val route = ReflectionHelpers.callConstructor<RouteInfo>(RouteInfo::class.java,
            ClassParameter.from(IpPrefix::class.java, destination), ClassParameter.from(InetAddress::class.java, null),
            ClassParameter.from(String::class.java, name))
        ReflectionHelpers.callInstanceMethod<Any>(this, "addRoute", ClassParameter.from(RouteInfo::class.java, route))
    }
    private fun capabilities(vpn: Boolean = true) = NetworkCapabilities().apply {
        ReflectionHelpers.callInstanceMethod<Any>(this, "addTransportType", ClassParameter.from(Integer.TYPE,
            if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_WIFI))
    }
    private fun add(id: Int, name: String, vpn: Boolean = true): Network {
        val network = ShadowNetwork.newInstance(id)
        val shadow = Shadows.shadowOf(manager)
        shadow.addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if (vpn) ConnectivityManager.TYPE_VPN else ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadow.setLinkProperties(network, properties(name))
        shadow.setNetworkCapabilities(network, capabilities(vpn))
        return network
    }
    @Before fun reset() {
        Shadows.shadowOf(manager).clearAllNetworks()
        VpnConnectivityShadow.current = null
        VpnConnectivityShadow.calls.clear()
        VpnConnectivityShadow.failures = 0
    }
    @Test fun findsOnlyTheOwnedVpnAndRestoresThePreviousProcessNetwork() {
        val previous = ShadowNetwork.newInstance(10)
        VpnConnectivityShadow.current = previous
        add(11, "tun-other"); add(12, "tun-test", false)
        val own = add(13, "tun-test")
        val messages = mutableListOf<String>()
        val lease = VpnNetworkBinding.acquire(context, "tun-test", AtomicBoolean(false), messages::add)
        assertEquals(own, VpnConnectivityShadow.current)
        lease.close()
        assertEquals(previous, VpnConnectivityShadow.current)
        assertEquals(listOf(own, previous), VpnConnectivityShadow.calls)
        assertTrue(messages.last().contains("已恢复"))
    }
    @Test fun registrationCanPrecedeNetdBindingReadiness() {
        val own = add(13, "tun-test")
        VpnConnectivityShadow.failures = 1
        VpnNetworkBinding.acquire(context, "tun-test", AtomicBoolean(false), {}).use {
            assertEquals(own, VpnConnectivityShadow.current)
        }
        assertEquals(listOf(own, own, null), VpnConnectivityShadow.calls)
    }
    @Test fun ambiguousNetworksAndCancellationNeverBind() {
        add(13, "tun-test"); add(14, "tun-test")
        assertThrows(IOException::class.java) { VpnNetworkBinding.acquire(context, "tun-test", AtomicBoolean(false), {}) }
        assertThrows(IOException::class.java) { VpnNetworkBinding.acquire(context, "tun-test", AtomicBoolean(true), {}) }
        assertTrue(VpnConnectivityShadow.calls.isEmpty())
    }
    @Test fun aMatchingInterfaceAloneCannotSelectAWrongOrIncompleteNetwork() {
        assertTrue(VpnNetworkBinding.matches("tun-test", properties("tun-test"), capabilities()))
        assertFalse(VpnNetworkBinding.matches("tun-test", properties("tun-test"), capabilities(false)))
        assertFalse(VpnNetworkBinding.matches("tun-test", properties("tun-other"), capabilities()))
        assertFalse(VpnNetworkBinding.matches("tun-test", properties("tun-test", "198.18.0.3"), capabilities()))
        assertFalse(VpnNetworkBinding.matches("tun-test", properties("tun-test", prefix = 16), capabilities()))
        assertFalse(VpnNetworkBinding.matches("tun-test", LinkProperties(), capabilities()))
        assertFalse(VpnNetworkBinding.matches("tun-test", null, null))
    }
}

/** Real candidate discovery with a simulated per-process binding, never native routing. */
@Implements(ConnectivityManager::class)
class VpnConnectivityShadow : ShadowConnectivityManager() {
    companion object {
        var current: Network? = null
        var failures = 0
        val calls = mutableListOf<Network?>()
        @JvmStatic @Implementation(minSdk = 21)
        fun getProcessDefaultNetwork(): Network? = current
        @JvmStatic @Implementation(minSdk = 21)
        fun setProcessDefaultNetwork(network: Network?): Boolean {
            calls += network
            if (failures > 0) { failures--; return false }
            current = network
            return true
        }
    }
}
