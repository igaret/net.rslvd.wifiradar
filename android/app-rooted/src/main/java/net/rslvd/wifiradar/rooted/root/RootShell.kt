package net.rslvd.wifiradar.rooted.root

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Thin wrapper around `su -c`. Every call spawns a fresh root shell; fine for setup commands. */
object RootShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        override fun toString(): String = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(stderr) }
            if (exitCode != 0) { if (isNotEmpty()) append(' '); append("(exit $exitCode)") }
        }
    }

    fun run(command: String, timeoutMs: Long = 10_000): Result {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: Exception) {
            return Result(-1, "", "cannot start su: ${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val tErr = thread(isDaemon = true) { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return Result(-1, out.toString().trim(), "timeout after ${timeoutMs} ms")
        }
        tOut.join(500); tErr.join(500)
        return Result(process.exitValue(), out.toString().trim(), err.toString().trim())
    }

    fun isAvailable(): Boolean = run("id", 5_000).let { it.ok && it.stdout.contains("uid=0") }

    /** Long-running root process (ping, tcpdump). Caller owns the Process. */
    fun start(command: String): Process = ProcessBuilder("su", "-c", command).start()
}
