package org.koitharu.kotatsu.core.image

import android.os.Parcel
import android.os.Parcelable
import android.view.View
import androidx.collection.ArrayMap
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import coil3.util.CoilUtils
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

@Parcelize
class CoilMemoryCacheKey(
    val data: MemoryCache.Key
) : Parcelable {

    companion object : Parceler<CoilMemoryCacheKey> {
        override fun CoilMemoryCacheKey.write(parcel: Parcel, flags: Int) {
            parcel.writeString(data.key)
            parcel.writeInt(data.extras.size)
            for ((key, value) in data.extras) {
                parcel.writeString(key)
                parcel.writeString(value)
            }
        }

        override fun create(parcel: Parcel): CoilMemoryCacheKey {
            val key = parcel.readString() ?: ""
            val size = parcel.readInt()
            val map = ArrayMap<String, String>(size)
            repeat(size) {
                val eKey = parcel.readString()
                val eValue = parcel.readString()
                if (eKey != null && eValue != null) {
                    map[eKey] = eValue
                }
            }
            return CoilMemoryCacheKey(MemoryCache.Key(key, map))
        }

        fun from(view: View): CoilMemoryCacheKey? {
            // Coil 3 returns a specific Result type; we safe cast to SuccessResult
            val result = CoilUtils.result(view)
            return (result as? SuccessResult)?.memoryCacheKey?.let {
                CoilMemoryCacheKey(it)
            }
        }
    }
}
