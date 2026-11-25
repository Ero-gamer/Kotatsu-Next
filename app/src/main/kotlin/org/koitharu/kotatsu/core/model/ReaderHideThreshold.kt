package org.koitharu.kotatsu.core.model

/** How far (dp) a webtoon has to scroll before the reader bars hide; [OFF] keeps them until toggled. */
enum class ReaderHideThreshold(val dp: Int) {
    OFF(0),
    LOW(48),
    HIGH(120),
    HIGHEST(300),
}
