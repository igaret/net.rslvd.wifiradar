package net.rslvd.wifiradar.rooted.nexmon

import android.os.Build
import net.rslvd.wifiradar.rooted.root.RootShell

/**
 * Broadcom chips with a public nexmon_csi patch. `packedFloat` chips deliver CSI as
 * 32-bit packed floating point words (unpack_float_acphy), the others as int16 I/Q.
 */
enum class NexmonChip(val label: String, val packedFloat: Boolean, val nman: Int, val nexp: Int, val firmwarePatch: String) {
    BCM4339("bcm4339 — Nexus 5 (hammerhead)", false, 0, 0, "bcm4339/6_37_34_43"),
    BCM4358("bcm4358 — Nexus 6P (angler)", true, 9, 5, "bcm4358/7_112_300_14_sta"),       // unpack_float format 0
    BCM43455C0("bcm43455c0 — Raspberry Pi 3B+/4B", false, 0, 0, "bcm43455c0/7_45_189"),
    BCM4366C0("bcm4366c0 — Asus RT-AC86U", true, 12, 6, "bcm4366c0/10_10_122_20"),       // unpack_float format 1
}

object DeviceCompat {
    private val byDevice = mapOf("hammerhead" to NexmonChip.BCM4339, "angler" to NexmonChip.BCM4358)

    fun detect(): NexmonChip? = byDevice[Build.DEVICE.lowercase()]

    fun summary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"

    private val candidates = listOf(
        "/system/bin/nexutil", "/system/xbin/nexutil", "/vendor/bin/nexutil",
        "/data/local/tmp/nexutil", "/data/adb/nexutil", "/sbin/nexutil",
    )

    /** Locates the nexutil binary with root; null if not installed. */
    fun findNexutil(): String? {
        val which = RootShell.run("which nexutil 2>/dev/null")
        if (which.ok && which.stdout.startsWith("/")) return which.stdout.lines().first().trim()
        val script = candidates.joinToString(" ") { "[ -x $it ] && echo $it && exit 0;" } + " exit 1"
        val r = RootShell.run(script)
        return if (r.ok) r.stdout.lines().first().trim().ifEmpty { null } else null
    }
}
