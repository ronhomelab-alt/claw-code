package com.vitafast.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Fires at each scheduled fast boundary: posts the notification, then re-arms tomorrow's alarm. */
public class FastAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String type = intent.getStringExtra("type");
        SharedPreferences p = Scheduler.prefs(ctx);
        int startMin = p.getInt("startMin", 1080);
        int endMin = p.getInt("endMin", 660);
        int durMin = ((endMin - startMin) + 1440) % 1440;
        if (durMin == 0) durMin = 1440;
        String dur = durMin % 60 == 0
                ? (durMin / 60) + "h"
                : (durMin / 60) + "h " + (durMin % 60) + "m";

        String title, body;
        if (Scheduler.TYPE_START.equals(type)) {
            title = "🌙 Fast started";
            body = "Your " + dur + " fast has begun. It ends at " + Scheduler.fmt(endMin) + ".";
        } else {
            title = "☀️ Fast complete!";
            body = "You fasted " + dur + ". Enjoy your eating window.";
        }

        PendingIntent tap = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, MainActivity.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class)
                .notify(Scheduler.TYPE_START.equals(type) ? 1 : 2, n);

        Scheduler.reschedule(ctx);
    }
}
