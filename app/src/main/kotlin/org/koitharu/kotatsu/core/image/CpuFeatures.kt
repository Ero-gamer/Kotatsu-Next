package org.koitharu.kotatsu.core.image

import android.os.Build
import android.os.Process
import java.io.File

/** Primary ABI and SIMD support, read once from /proc/cpuinfo (shown in the image info sheet). */
object CpuFeatures {

    /** ABI of this process (a 32-bit app on a 64-bit device runs the 32-bit ABI, not the device's primary one). */
    val abi: String = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
        .firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    /** True if the CPU reports NEON (armv7 "neon") or AdvSIMD (arm64 "asimd"); null if it could not be read. */
    val hasNeon: Boolean? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.split(' ')
                    ?.any { it == "neon" || it == "asimd" }
            }
        }.getOrNull()
    }

    fun describe(): String = when (hasNeon) {
        true -> "$abi · NEON"
        false -> "$abi · no NEON"
        null -> abi
    }
}
