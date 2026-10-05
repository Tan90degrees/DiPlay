package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Desktop-only replacement of the Android bulk host boundary, backed by real Apple USB service sockets. */
class Iap2UsbMuxHost(private val connector: (Int, Long) -> BlockingDuplexByteStream) : Closeable {
    fun connect(destinationPort: Int = LOCKDOWN_PORT, timeoutMillis: Long = 5_000): BlockingDuplexByteStream =
        connector(destinationPort, timeoutMillis)
    override fun close() { }
    companion object { const val LOCKDOWN_PORT = 62078 }
}
