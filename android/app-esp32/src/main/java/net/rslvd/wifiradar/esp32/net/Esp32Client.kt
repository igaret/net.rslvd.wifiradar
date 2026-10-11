package net.rslvd.wifiradar.esp32.net

import android.util.Log
import net.rslvd.wifiradar.core.model.CsiFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** TCP client for one ESP32: streams CSI/STATUS/TEXT frames, sends text commands. */
class Esp32Client(private val scope: CoroutineScope) {

    sealed class State {
        object Disconnected : State()
        data class Connecting(val host: String, val port: Int) : State()
        data class Connected(val host: String, val port: Int) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state = _state.asStateFlow()
    private val _frames = MutableSharedFlow<CsiFrame>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames = _frames.asSharedFlow()
    private val _status = MutableStateFlow<Esp32Protocol.Status?>(null)
    val status = _status.asStateFlow()
    private val _textLines = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val textLines = _textLines.asSharedFlow()

    val frameCount = AtomicLong()
    val resyncCount = AtomicLong()

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var job: Job? = null

    fun connect(host: String, port: Int = Esp32Protocol.DEFAULT_TCP_PORT) {
        disconnect()
        job = scope.launch(Dispatchers.IO) { run(host, port) }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        closeSocket()
        _state.value = State.Disconnected
        _status.value = null
    }

    fun send(command: String) {
        val out = output ?: return
        scope.launch(Dispatchers.IO) {
            try {
                out.write((command.trim() + "\n").toByteArray())
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "send failed: ${e.message}")
            }
        }
    }

    /** Sends a command and waits for its reply (OK/ERR/INFO line). Null on timeout. */
    suspend fun request(command: String, timeoutMs: Long = 3000): String? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val reply = async { _textLines.first { it.startsWith("OK") || it.startsWith("ERR") || it.startsWith("INFO") } }
            send(command)
            reply.await()
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        output = null
    }

    private suspend fun run(host: String, port: Int) {
        _state.value = State.Connecting(host, port)
        var failure: String? = null
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.soTimeout = 6000          // STATUS arrives every second; 6 s of silence means the link died
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s
            output = s.getOutputStream()
            _state.value = State.Connected(host, port)
            frameCount.set(0)
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val header = ByteArray(Esp32Protocol.HEADER_LEN)
            while (coroutineContext.isActive) {
                readHeader(input, header)
                val type = header[2].toInt() and 0xff
                val len = (header[4].toInt() and 0xff) or ((header[5].toInt() and 0xff) shl 8)
                val payload = ByteArray(len)
                input.readFully(payload)
                when (type) {
                    Esp32Protocol.TYPE_CSI -> Esp32Protocol.parseCsi(payload, System.currentTimeMillis())?.let {
                        frameCount.incrementAndGet()
                        _frames.tryEmit(it)
                    }
                    Esp32Protocol.TYPE_STATUS -> Esp32Protocol.parseStatus(payload)?.let { _status.value = it }
                    Esp32Protocol.TYPE_TEXT -> _textLines.tryEmit(String(payload, Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            if (coroutineContext.isActive) failure = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { s.close() }
            if (socket === s) {                 // only tear down if connect() has not replaced us meanwhile
                socket = null
                output = null
                _state.value = failure?.let { State.Error(it) } ?: State.Disconnected
            }
        }
    }

    /** Reads a 6-byte header, sliding byte-by-byte until the magic matches (resync after corruption). */
    private fun readHeader(input: DataInputStream, header: ByteArray) {
        input.readFully(header)
        while (((header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)) != Esp32Protocol.MAGIC) {
            System.arraycopy(header, 1, header, 0, header.size - 1)
            header[header.size - 1] = input.readByte()
            resyncCount.incrementAndGet()
        }
    }

    companion object {
        private const val TAG = "Esp32Client"

        /** Connects, sends one command, returns its reply. Used to configure the TX from dual mode. */
        suspend fun oneShot(host: String, port: Int, command: String, timeoutMs: Int = 4000): String? =
            withContext(Dispatchers.IO) {
                try {
                    Socket().use { s ->
                        s.soTimeout = timeoutMs
                        s.connect(InetSocketAddress(host, port), timeoutMs)
                        s.getOutputStream().apply { write("STOP\n$command\n".toByteArray()); flush() }
                        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                        val header = ByteArray(Esp32Protocol.HEADER_LEN)
                        val deadline = System.currentTimeMillis() + timeoutMs
                        while (System.currentTimeMillis() < deadline) {
                            input.readFully(header)
                            val type = header[2].toInt() and 0xff
                            val len = (header[4].toInt() and 0xff) or ((header[5].toInt() and 0xff) shl 8)
                            val payload = ByteArray(len).also { input.readFully(it) }
                            if (type == Esp32Protocol.TYPE_TEXT) {
                                val text = String(payload, Charsets.UTF_8)
                                if (!text.startsWith("OK STOP")) return@withContext text
                            }
                        }
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "oneShot $command to $host failed: ${e.message}")
                    null
                }
            }
    }
}
