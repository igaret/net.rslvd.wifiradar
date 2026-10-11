package net.rslvd.wifiradar.rooted.nexmon

import net.rslvd.wifiradar.rooted.root.RootShell

/**
 * Connected-mode illuminator: pings the access point as root (root may use intervals
 * below the 200 ms non-root floor). Each echo reply from the AP yields one CSI sample,
 * so filtering on the AP's BSSID gives a clean, regularly sampled stream.
 */
class PingGenerator {
    private var process: Process? = null
    private var command: String? = null

    val isRunning: Boolean get() = process?.isAlive == true

    fun start(gateway: String, rateHz: Int) {
        stop()
        // Locale.US: a German/French locale would produce "0,020", which ping rejects
        val interval = "%.3f".format(java.util.Locale.US, 1.0 / rateHz.coerceIn(1, 200))
        val cmd = "ping -i $interval -s 56 -q $gateway"
        command = cmd
        process = RootShell.start("$cmd > /dev/null 2>&1")
    }

    fun stop() {
        process?.destroy()
        process = null
        command?.let { RootShell.run("pkill -f '$it'", 3000) }   // su may not forward SIGTERM to the child
        command = null
    }
}
