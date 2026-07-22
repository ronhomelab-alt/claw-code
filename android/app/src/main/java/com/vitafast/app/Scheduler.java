package com.vitafast.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/** Persists the fasting schedule and arms exact alarms for the next start/end. */
public class Scheduler {

    static final String PREFS = "vitafast";
    static final String TYPE_START = "start";
    static final String TYPE_END = "end";
    private static final int REQ_START = 1;
    private static final int REQ_END = 2;

    static void saveAndReschedule(Context ctx, int startMin, int endMin,
                                  boolean enabled, boolean notify) {
        prefs(ctx).edit()
                .putInt("startMin", startMin)
                .putInt("endMin", endMin)
                .putBoolean("enabled", enabled)
                .putBoolean("notify", notify)
                .apply();
        reschedule(ctx);
    }

    static void reschedule(Context ctx) {
        SharedPreferences p = prefs(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        cancel(ctx, am, REQ_START, TYPE_START);
        cancel(ctx, am, REQ_END, TYPE_END);
        if (!p.getBoolean("enabled", false) || !p.getBoolean("notify", false)) return;

        setAlarm(ctx, am, REQ_START, TYPE_START, nextOccurrence(p.getInt("startMin", 1080)));
        setAlarm(ctx, am, REQ_END, TYPE_END, nextOccurrence(p.getInt("endMin", 660)));
    }

    private static void setAlarm(Context ctx, AlarmManager am, int req, String type, long at) {
        PendingIntent pi = pending(ctx, req, type);
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT < 31) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            // Exact alarms not granted (Android 14+ default) — fire within a window
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi);
        }
    }

    private static void cancel(Context ctx, AlarmManager am, int req, String type) {
        am.cancel(pending(ctx, req, type));
    }

    private static PendingIntent pending(Context ctx, int req, String type) {
        Intent i = new Intent(ctx, FastAlarmReceiver.class).putExtra("type", type);
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static long nextOccurrence(int minOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60);
        c.set(Calendar.MINUTE, minOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    static String fmt(int minOfDay) {
        int h = minOfDay / 60, m = minOfDay % 60;
        String ap = h >= 12 ? "PM" : "AM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format("%d:%02d %s", h12, m, ap);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
