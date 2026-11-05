package com.llamaaistudio.app.engine

import java.io.File

/**
 * Big.LITTLE-aware core counts from sysfs. Tensor G4 (Pixel 9) is 1× Cortex-X4 + 3× A720 +
 * 4× A520: token generation is memory-bound and runs fastest on the 4 fast cores only (the
 * efficiency cores make every step wait for the slowest thread), while prompt processing is
 * compute-bound and does benefit from all 8.
 */
object CpuTopology {
    val totalCores: Int by lazy { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    /** Max frequency (kHz) per core, from sysfs; empty if the kernel hides it. */
    private val maxFreqs: List<Long> by lazy {
        (0 until totalCores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
    }

    /** Cores outside the slowest (efficiency) cluster, or a safe fallback. */
    val performanceCores: Int by lazy {
        val freqs = maxFreqs
        if (freqs.size < 2) return@lazy (totalCores / 2).coerceAtLeast(1)
        val slowest = freqs.minOrNull() ?: 0L
        val fast = freqs.count { it > slowest }
        if (fast == 0) totalCores else fast // all cores identical: use them all
    }

    /** Human-readable summary, e.g. "8 cores: 1× 3.1 GHz, 3× 2.6 GHz, 4× 1.9 GHz". */
    val description: String by lazy {
        val groups = maxFreqs.groupingBy { it }.eachCount().toSortedMap(compareByDescending { it })
        if (groups.isEmpty()) "$totalCores cores"
        else "$totalCores cores: " + groups.entries.joinToString(", ") { (f, n) -> "$n× %.1f GHz".format(f / 1_000_000.0) }
    }
}
