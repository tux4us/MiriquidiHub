package io.github.tux4us.miriquidihub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Speichern von Screenshots (MediaStore, Pictures/MiriquidiHub) und Hinweis zum Bearbeiten. */
object ScreenshotStore {

    private const val CHANNEL_ID = "screenshot_saved"
    private const val NOTIFICATION_ID = 2002

    /** Speichert [bitmap] als PNG und liefert die Uri; null bei Fehler. Das Bitmap wird nicht recycelt. */
    fun save(context: Context, bitmap: Bitmap, suffix: String = ""): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Screenshot_$timeStamp$suffix.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "MiriquidiHub")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                ?: throw java.io.IOException("Kein Ausgabestrom")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Zeigt eine Benachrichtigung, über die der Screenshot im Editor geöffnet wird. */
    fun notifyEditable(context: Context, uri: Uri) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.screenshot_saved_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(context, ScreenshotEditorActivity::class.java).apply {
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            context, uri.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.screenshot_saved))
            .setContentText(context.getString(R.string.screenshot_tap_to_edit))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Benachrichtigungen nicht erlaubt – Screenshot bleibt trotzdem gespeichert
        }
    }
}
