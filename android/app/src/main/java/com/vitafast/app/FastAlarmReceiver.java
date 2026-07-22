package com.vitafast.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Fires at each scheduled boundary (start / phase milestone / end): posts the
 * matching alert, then re-arms the schedule (which also refreshes or clears the
 * ongoing live-status notification).
 */
public class FastAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String type = intent.getStringExtra("type");
        long dur = intent.getLongExtra("dur", 960);
        int endMin = intent.getIntExtra("endMin", 660);
        String durStr = (dur % 60 == 0) ? (dur / 60 + "h") : (dur / 60 + "h " + dur % 60 + "m");

        SharedPreferences p = Scheduler.prefs(ctx);
        JSONObject c;
        try { c = new JSONObject(p.getString("config", "{}")); } catch (Exception e) { c = new JSONObject(); }

        if ("start".equals(type)) {
            if (c.optBoolean("notify", false))
                post(ctx, 1, "🌙 Fast started",
                        "Your " + durStr + " fast has begun. It ends at " + Scheduler.fmt(endMin) + ".");
        } else if ("end".equals(type)) {
            if (c.optBoolean("notify", false))
                post(ctx, 2, "☀️ Fast complete!",
                        "You fasted " + durStr + ". Enjoy your eating window.");
        } else if ("milestone".equals(type)) {
            if (c.optBoolean("milestones", false)) {
                String name = intent.getStringExtra("pname");
                String icon = intent.getStringExtra("picon");
                if (name == null) name = "New phase";
                if (icon == null) icon = "✨";
                post(ctx, 10, icon + " " + name,
                        "You've entered the " + name + " phase of your fast.");
            }
        }

        Scheduler.reschedule(ctx);
    }

    private void post(Context ctx, int id, String title, String body) {
        PendingIntent tap = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, MainActivity.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(id, n);
    }
}
