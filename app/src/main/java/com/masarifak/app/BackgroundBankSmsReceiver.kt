package com.masarifak.app

import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.NotificationCompat

class BackgroundBankSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullBody = messages.joinToString("") { it.displayMessageBody ?: "" }
        val sender = messages[0].displayOriginatingAddress ?: ""
        val lower = (sender + " " + fullBody).lowercase()

        val isBankSms = listOf(
            "alrajhi", "الراجحي", "snb", "الأهلي", "alinma", "الإنماء", "riyad", "الرياض",
            "albilad", "البلاد", "aljazira", "الجزيرة", "anb", "العربي", "stcpay", "urpay", "barq", "tiqmo"
        ).any { lower.contains(it) } || lower.contains("شراء") || lower.contains("خصم") || lower.contains("إيداع")

        if (isBankSms) {
            // Persist a bounded queue so the app can process bank messages received
            // while its Activity/WebView is stopped or the process is recreated.
            try {
                val prefs = context.getSharedPreferences("msf_background_sms", Context.MODE_PRIVATE)
                val queue = JSONArray(prefs.getString("pending_bank_sms", "[]") ?: "[]")
                val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                val stableId = "bg_${timestamp}_${(sender + "|" + fullBody).hashCode()}"
                var alreadyQueued = false
                for (i in 0 until queue.length()) {
                    if (queue.optJSONObject(i)?.optString("id") == stableId) { alreadyQueued = true; break }
                }
                if (!alreadyQueued) {
                    val item = JSONObject()
                        .put("id", stableId)
                        .put("sender", URLEncoder.encode(sender, "UTF-8").replace("+", "%20"))
                        .put("body", URLEncoder.encode(fullBody, "UTF-8").replace("+", "%20"))
                        .put("date", timestamp.toString())
                    queue.put(item)
                }
                val bounded = JSONArray()
                val start = maxOf(0, queue.length() - 100)
                for (i in start until queue.length()) bounded.put(queue.getJSONObject(i))
                prefs.edit().putString("pending_bank_sms", bounded.toString()).apply()
            } catch (_: Exception) {}

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(
                    "masarifak_sms_channel", "إشعارات مصاريفك", NotificationManager.IMPORTANCE_HIGH
                ))
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, "masarifak_sms_channel")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("مصاريفك: إشعار عملية بنكية ($sender)")
                .setContentText(fullBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        }
    }
}
