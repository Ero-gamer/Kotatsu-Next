package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Rect
import com.radzivon.vicvane.android.avif.HeifCoder

class AvifRegionDecoderCompat(private val bytes: ByteArray) {
    private val heifCoder = HeifCoder()
    
    // Get full image size without loading whole bitmap if possible
    // Note: avif-coder usually needs a full decode for now, but we'll optimize
    private val info = heifCoder.decode(bytes) 
    val width: Int = info?.width ?: 0
    val height: Int = info?.height ?: 0

    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        val fullBitmap = info ?: return null
        
        // Create the cropped region
        val region = Bitmap.createBitmap(fullBitmap, rect.left, rect.top, rect.width(), rect.height())
        
        return if (sampleSize > 1) {
            val scaled = Bitmap.createScaledBitmap(
                region, 
                rect.width() / sampleSize, 
                rect.height() / sampleSize, 
                true
            )
            region.recycle()
            scaled
        } else {
            region
        }
    }

    fun recycle() {
        info?.recycle()
    }
}
