package net.rslvd.wifiradar.rooted.nexmon

import net.rslvd.wifiradar.rooted.root.RootShell

/**
 * Drives the nexmon-patched firmware through `nexutil`:
 *   nexutil -I<iface> -s500 -b -l34 -v<params>   write CSI parameters (ioctl 500)
 *   nexutil -I<iface> -m1 / -m0                  monitor mode on/off (passive mode only)
 */
class NexmonController(var iface: String = "wlan0", var nexutilPath: String = "nexutil") {

    data class Preflight(val root: Boolean, val nexutil: String?, val detectedChip: NexmonChip?, val report: String)

    private val log = ArrayDeque<String>()

    @Synchronized
    private fun record(line: String) {
        log.addLast(line)
        while (log.size > 40) log.removeFirst()
    }

    /** Returns and clears the accumulated command log (start/stop may run on different threads). */
    @Synchronized
    fun drain(): List<String> = log.toList().also { log.clear() }

    fun preflight(): Preflight {
        val sb = StringBuilder()
        sb.appendLine(DeviceCompat.summary())
        val root = RootShell.isAvailable()
        sb.appendLine(if (root) "✓ root access" else "✗ no root (su failed)")
        val nexutil = if (root) DeviceCompat.findNexutil() else null
        if (nexutil != null) {
            nexutilPath = nexutil
            val v = RootShell.run("$nexutil -v 2>&1 | head -2")
            sb.appendLine("✓ nexutil at $nexutil ${v.stdout.lines().firstOrNull()?.let { "($it)" } ?: ""}")
        } else {
            sb.appendLine("✗ nexutil not found — install nexmon + nexmon_csi for your chip first")
        }
        val chip = DeviceCompat.detect()
        sb.appendLine(chip?.let { "✓ known device → ${it.label}, patch ${it.firmwarePatch}" }
            ?: "? device not in the known list; pick the chip manually if your firmware is patched")
        if (root) {
            val ifc = RootShell.run("ip -o link show $iface 2>&1 | head -1")
            sb.appendLine(if (ifc.ok && ifc.stdout.isNotEmpty()) "✓ interface $iface present" else "✗ interface $iface missing")
        }
        return Preflight(root, nexutil, chip, sb.toString().trim())
    }

    private fun exec(cmd: String): RootShell.Result {
        val r = RootShell.run(cmd, 15_000)
        record("# $cmd")
        if (r.toString().isNotEmpty()) record(r.toString())
        return r
    }

    /** Writes the CSI parameters; optionally switches to monitor mode. */
    fun start(cfg: CsiParams.Config, monitorMode: Boolean): Result<String> {
        val b64 = CsiParams.encodeBase64(cfg)
        exec("ifconfig $iface up")
        val r = exec("$nexutilPath -I$iface -s500 -b -l34 -v$b64")
        if (!r.ok) return Result.failure(IllegalStateException(
            "nexutil ioctl 500 failed (${r}). Is the nexmon_csi firmware loaded on the chip?"))
        if (monitorMode) {
            val m = exec("$nexutilPath -I$iface -m1")
            if (!m.ok) return Result.failure(IllegalStateException("could not enable monitor mode: $m"))
        }
        return Result.success("CSI parameters written: chanspec 0x%04x, %d MAC filter(s), params %s"
            .format(CsiParams.chanspec(cfg.channel, cfg.bandwidthMhz), cfg.macFilters.size, b64))
    }

    fun stop(cfg: CsiParams.Config, monitorMode: Boolean) {
        exec("$nexutilPath -I$iface -s500 -b -l34 -v${CsiParams.encodeBase64(cfg.copy(collect = false))}")
        if (monitorMode) exec("$nexutilPath -I$iface -m0")
    }
}
