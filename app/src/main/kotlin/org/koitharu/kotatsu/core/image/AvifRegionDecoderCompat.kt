package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Rect
import com.github.awxkee.avifcoder.HeifCoder // FIXED: Updated Import

class AvifRegionDecoderCompat(private val bytes: ByteArray) {
    private val heifCoder = HeifCoder()
    
    // In avif-coder 2.x, sizing is now handled via getSize() for efficiency
    private val size = heifCoder.getSize(bytes)
    val width: Int = size?.width ?: 0
    val height: Int = size?.height ?: 0

    // Cache the decoded bitmap for actual region extraction
    private var fullBitmap: Bitmap? = null

    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        if (fullBitmap == null) {
            fullBitmap = heifCoder.decode(bytes)
        }
        
        val bitmap = fullBitmap ?: return null
        
        // Ensure rect bounds are within the actual bitmap dimensions to prevent crash
        val safeWidth = rect.width().coerceAtMost(bitmap.width - rect.left)
        val safeHeight = rect.height().coerceAtMost(bitmap.height - rect.top)
        
        val region = Bitmap.createBitmap(bitmap, rect.left, rect.top, safeWidth, safeHeight)
        
        return if (sampleSize > 1) {
            val scaled = Bitmap.createScaledBitmap(
                region, 
                safeWidth / sampleSize, 
                safeHeight / sampleSize, 
                true
            )
            region.recycle()
            scaled
        } else {
            region
        }
    }

    fun recycle() {
        fullBitmap?.recycle()
        fullBitmap = null
    }
}
