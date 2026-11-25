package org.koitharu.kotatsu.core.exceptions

import okio.IOException

/** Not enough free storage to start the next unit of work (checked before writing, so no file is left truncated). */
class NotEnoughSpaceException(
    val freeBytes: Long,
    val neededBytes: Long,
) : IOException("Not enough free space: $freeBytes bytes available, $neededBytes needed")
