package dev.ha_iroh.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import uniffi.ha_iroh_core.CoreException
import uniffi.ha_iroh_core.Stream
import uniffi.ha_iroh_core.Tunnel

/**
 * A Socket whose bytes ride one iroh stream. connect() ignores the address and opens the stream.
 */
class IrohSocket(private val tunnel: Tunnel) : Socket() {
    private var stream: Stream? = null
    private var closed = false
    private var outShut = false
    private var inShut = false
    private var addr: SocketAddress? = null
    private var soTimeoutMs = 0

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        stream = tunnel.openStream()
        addr = endpoint
    }

    private fun s(): Stream {
        if (closed) throw SocketException("closed")
        return stream ?: throw java.io.IOException("not connected")
    }

    override fun isConnected() = stream != null
    override fun isClosed() = closed
    override fun isInputShutdown() = inShut
    override fun isOutputShutdown() = outShut
    override fun getRemoteSocketAddress() = addr
    override fun getInetAddress(): InetAddress? = (addr as? InetSocketAddress)?.address
    override fun getPort() = (addr as? InetSocketAddress)?.port ?: 0
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

    // Socket options OkHttp sets; nothing to do for a stream.
    override fun setSoTimeout(timeout: Int) { soTimeoutMs = timeout }
    override fun getSoTimeout() = soTimeoutMs
    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}

    override fun shutdownOutput() {
        outShut = true
        try { s().shutdownWrite() } catch (e: CoreException) { throw IOException(e) }
    }

    override fun shutdownInput() {
        inShut = true
    }

    override fun close() {
        closed = true
        stream?.disconnect()
    }

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val data = try {
                s().read(len.toUInt(), soTimeoutMs.toUInt())
            } catch (e: CoreException.Timeout) {
                throw SocketTimeoutException("read timed out")
            } catch (e: CoreException.Closed) {
                throw SocketException("closed")
            } catch (e: CoreException) {
                throw IOException(e) // OkHttp background threads only tolerate IOException
            }
            if (data.isEmpty()) return -1
            data.copyInto(b, off)
            return data.size
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            // tunnel already gone (e.g. close frame written after disconnect) must not crash OkHttp's writer thread
            try { s().write(b.copyOfRange(off, off + len)) } catch (e: CoreException) { throw IOException(e) }
        }
    }
}

class IrohSocketFactory(private val tunnel: Tunnel) : SocketFactory() {
    override fun createSocket(): Socket = IrohSocket(tunnel)
    override fun createSocket(host: String?, port: Int): Socket = createSocket()
    override fun createSocket(host: String?, port: Int, l: InetAddress?, lp: Int): Socket = createSocket()
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
    override fun createSocket(a: InetAddress?, port: Int, l: InetAddress?, lp: Int): Socket = createSocket()
}

/** One client per Tunnel. Base URL: http://ha.iroh/ (iroh already encrypts). */
fun Tunnel.okHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(IrohSocketFactory(this))
    .dns(Dns { listOf(InetAddress.getByAddress("ha.iroh", byteArrayOf(127, 0, 0, 1))) })
    .protocols(listOf(Protocol.HTTP_1_1))
    .retryOnConnectionFailure(false)
    // each stream is a fresh TCP conn to HA; without soTimeout, pooled-conn health checks would block
    .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
    .build()
