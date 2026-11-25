package org.koitharu.kotatsu.core.model

/** Color of the translucent reader overlay (blue-light filtering, night reading); [OFF] draws nothing. */
enum class ReaderTint(val rgb: Int) {
    OFF(0x000000),
    AMBER(0xFF9800),
    SEPIA(0x704214),
    RED(0xE53935),
    GREEN(0x2E7D32),
    BLUE(0x1E88E5),
}
