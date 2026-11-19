package io.github.tux4us.miriquidihub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import io.github.tux4us.miriquidihub.files.ThumbMath
import java.io.File
import java.util.concurrent.Executors

/**
 * Lädt Vorschaubilder lokaler Bilder und Videos im Hintergrund und hält sie in einem Speicher-Cache.
 * Die Zuordnung zur [ImageView] erfolgt über deren Tag; beim Recycling verworfene Ergebnisse werden ignoriert.
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(1 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** true, wenn für diesen Typ ein Vorschaubild versucht wird. */
    fun supports(mime: String): Boolean =
        (mime.startsWith("image/") && mime != "image/svg+xml") || mime.startsWith("video/")

    /** Liefert das gecachte Bild oder null. */
    fun cached(key: String): Bitmap? = cache.get(key)

    /**
     * Startet das Laden. Ergebnis wird nur gesetzt, wenn [view] noch das Tag [key] trägt.
     * [onLoaded] läuft im Hauptthread.
     */
    fun load(view: ImageView, key: String, file: File, mime: String, targetPx: Int, onLoaded: (Bitmap) -> Unit) {
        synchronized(failed) { if (key in failed) return }
        executor.execute {
            if (view.tag != key) return@execute
            val bmp = try {
                if (mime.startsWith("video/")) decodeVideo(file, targetPx) else decodeImage(file, targetPx)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp == null) {
                synchronized(failed) { failed.add(key) }
                return@execute
            }
            cache.put(key, bmp)
            main.post { if (view.tag == key) onLoaded(bmp) }
        }
    }

    private fun decodeImage(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ThumbMath.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun decodeVideo(file: File, target: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime() ?: return null
            val shortSide = minOf(frame.width, frame.height)
            if (shortSide <= target) return frame
            val scale = target.toFloat() / shortSide
            val scaled = Bitmap.createScaledBitmap(
                frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true
            )
            if (scaled !== frame) frame.recycle()
            return scaled
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
