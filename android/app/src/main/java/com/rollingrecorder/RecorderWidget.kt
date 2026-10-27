package com.rollingrecorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: one tap starts listening when stopped, stops it when running. */
class RecorderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        /** Call whenever the recorder's state changes so the widget shows it. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val listening = RecorderState.status.value != Status.STOPPED
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val intent = Intent(context, RecorderService::class.java)
                .setAction(if (listening) RecorderService.ACTION_STOP else RecorderService.ACTION_START)
            val tap = if (listening) PendingIntent.getService(context, 2, intent, flags)
            else PendingIntent.getForegroundService(context, 3, intent, flags)
            return RemoteViews(context.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_text, if (listening) "Listening" else "Start")
                setInt(R.id.widget_root, "setBackgroundResource", if (listening) R.drawable.widget_bg_on else R.drawable.widget_bg)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }
    }
}
