package com.simpleconverter.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.simpleconverter.app.MainActivity
import com.simpleconverter.app.R

object Notifications {
    private const val CHANNEL_PROGRESS = "conversion"
    private const val CHANNEL_RESULT = "results"
    const val ID_PROGRESS = 1
    private const val ID_RESULT = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Umwandlung läuft", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Umwandlung fertig", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun progress(context: Context, title: String, text: String, progress: Int, cancel: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Abbrechen", cancel)
            .build()

    fun done(context: Context, name: String, detail: String, uri: Uri, mime: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Teilen",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, 1, view, flags)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Fertig: $name")
                .setContentText(detail)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "Öffnen", open)
                .addAction(0, "Teilen", PendingIntent.getActivity(context, 2, share, flags))
                .build(),
        )
    }

    fun batchDone(context: Context, ok: Int, total: Int, detail: String) {
        val title = if (ok == total) "$total Dateien fertig" else "$ok von $total Dateien fertig"
        val text = if (ok == total) detail else "${total - ok} fehlgeschlagen · $detail"
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun failed(context: Context, name: String, message: String) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Fehlgeschlagen: $name")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun cancelResult(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_RESULT)
    }

    fun update(context: Context, id: Int, notification: Notification) {
        if (allowed(context)) context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun post(context: Context, notification: Notification) = update(context, ID_RESULT, notification)

    private fun allowed(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
