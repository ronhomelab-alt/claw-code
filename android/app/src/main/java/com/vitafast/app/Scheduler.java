package com.vitafast.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Parses the schedule config pushed from the web layer and arms exact alarms for
 * the current-or-next fast: its start, each phase milestone, and its end. Also
 * maintains the ongoing "live status" notification while a fast is in progress.
 */
public class Scheduler {

    static final String PREFS = "vitafast";
    static final int REQ_START = 1000;
    static final int REQ_END = 1001;
    static final int REQ_MS_BASE = 1100;   // + phase index
    static final int ONGOING_ID = 99;
    private static final int DAY = 1440;

    static void saveConfig(Context ctx, String json) {
        prefs(ctx).edit().putString("config", json).apply();
        reschedule(ctx);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject config(Context ctx) {
        try { return new JSONObject(prefs(ctx).getString("config", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    // ---- day model ------------------------------------------------------

    /** {start, end, active(1/0)} minutes-of-day for a day-of-week (0=Sun). */
    private static int[] dayOf(JSONObject c, int dow) {
        try {
            JSONArray days = c.getJSONArray("days");
            JSONObject d = days.getJSONObject(dow);
            return new int[]{ d.getInt("start"), d.getInt("end"),
                    d.optBoolean("active", true) ? 1 : 0 };
        } catch (Exception e) {
            return new int[]{1080, 660, 1};
        }
    }

    private static int durMin(int[] d) {
        int v = ((d[1] - d[0]) % DAY + DAY) % DAY;
        return v == 0 ? DAY : v;
    }

    private static Calendar midnight(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /** [startMs, endMs, durMin] of the fast covering now, or null. */
    private static long[] activeFast(JSONObject c, long now) {
        for (int back = 1; back >= 0; back--) {
            Calendar m = midnight(now);
            m.add(Calendar.DAY_OF_YEAR, -back);
            int[] d = dayOf(c, m.get(Calendar.DAY_OF_WEEK) - 1);
            if (d[2] == 0) continue;
            long start = m.getTimeInMillis() + d[0] * 60000L;
            long end = start + durMin(d) * 60000L;
            if (now >= start && now < end) return new long[]{start, end, durMin(d)};
        }
        return null;
    }

    /** [startMs, durMin] of the next fast starting after `from`, or null. */
    private static long[] nextStart(JSONObject c, long from) {
        for (int k = 0; k < 8; k++) {
            Calendar m = midnight(from);
            m.add(Calendar.DAY_OF_YEAR, k);
            int[] d = dayOf(c, m.get(Calendar.DAY_OF_WEEK) - 1);
            if (d[2] == 0) continue;
            long start = m.getTimeInMillis() + d[0] * 60000L;
            if (start > from) return new long[]{start, durMin(d)};
        }
        return null;
    }

    // ---- scheduling -----------------------------------------------------

    static void reschedule(Context ctx) {
        JSONObject c = config(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);

        // clear everything we might have set
        cancel(ctx, am, REQ_START, "start");
        cancel(ctx, am, REQ_END, "end");
        for (int i = 0; i < 12; i++) cancel(ctx, am, REQ_MS_BASE + i, "milestone");

        if (!c.optBoolean("enabled", false)) { cancelOngoing(ctx); return; }

        long now = System.currentTimeMillis();
        long[] af = activeFast(c, now);
        long fastStart, fastEnd, dur;
        boolean inProgress = af != null;

        if (inProgress) {
            fastStart = af[0]; fastEnd = af[1]; dur = af[2];
            long[] ns = nextStart(c, fastEnd);          // arm the following fast too
            if (ns != null) setAlarm(ctx, am, REQ_START, "start", ns[0], ns[1], endMinOf(ns[0], ns[1]));
        } else {
            long[] ns = nextStart(c, now);
            if (ns == null) { cancelOngoing(ctx); return; }
            fastStart = ns[0]; dur = ns[1]; fastEnd = fastStart + dur * 60000L;
            setAlarm(ctx, am, REQ_START, "start", fastStart, dur, endMinOf(fastStart, dur));
        }

        int endMin = endMinOf(fastStart, dur);
        if (fastEnd > now) setAlarm(ctx, am, REQ_END, "end", fastEnd, dur, endMin);

        // phase milestones inside this fast
        if (c.optBoolean("milestones", false)) {
            JSONArray phases = c.optJSONArray("phases");
            if (phases != null) {
                for (int i = 0; i < phases.length(); i++) {
                    JSONObject p = phases.optJSONObject(i);
                    if (p == null) continue;
                    double at = p.optDouble("at", -1);
                    if (at <= 0 || at * 60 >= dur) continue;
                    long t = fastStart + (long) (at * 3600000L);
                    if (t <= now) continue;
                    setMilestone(ctx, am, REQ_MS_BASE + i, t, dur, endMin,
                            p.optString("name", "New phase"), p.optString("icon", "✨"));
                }
            }
        }

        // ongoing live-status notification
        if (inProgress && c.optBoolean("ongoing", false)) postOngoing(ctx, c, fastStart, fastEnd, dur, now);
        else cancelOngoing(ctx);
    }

    private static int endMinOf(long startMs, long durMin) {
        Calendar e = Calendar.getInstance();
        e.setTimeInMillis(startMs + durMin * 60000L);
        return e.get(Calendar.HOUR_OF_DAY) * 60 + e.get(Calendar.MINUTE);
    }

    private static void setAlarm(Context ctx, AlarmManager am, int req, String type,
                                 long at, long dur, int endMin) {
        Intent i = new Intent(ctx, FastAlarmReceiver.class)
                .putExtra("type", type).putExtra("dur", dur).putExtra("endMin", endMin);
        fire(am, at, PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static void setMilestone(Context ctx, AlarmManager am, int req, long at,
                                     long dur, int endMin, String name, String icon) {
        Intent i = new Intent(ctx, FastAlarmReceiver.class)
                .putExtra("type", "milestone").putExtra("dur", dur).putExtra("endMin", endMin)
                .putExtra("pname", name).putExtra("picon", icon);
        fire(am, at, PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static void fire(AlarmManager am, long at, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancel(Context ctx, AlarmManager am, int req, String type) {
        Intent i = new Intent(ctx, FastAlarmReceiver.class).putExtra("type", type);
        am.cancel(PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    // ---- ongoing notification ------------------------------------------

    private static void postOngoing(Context ctx, JSONObject c, long startMs, long endMs,
                                    long dur, long now) {
        double elapsedH = (now - startMs) / 3600000.0;
        String phase = "Fed state", icon = "🍽️";
        JSONArray phases = c.optJSONArray("phases");
        if (phases != null) {
            for (int i = 0; i < phases.length(); i++) {
                JSONObject p = phases.optJSONObject(i);
                if (p != null && elapsedH >= p.optDouble("at", 1e9)) {
                    phase = p.optString("name", phase); icon = p.optString("icon", icon);
                }
            }
        }
        String durStr = (dur % 60 == 0) ? (dur / 60 + "h") : (dur / 60 + "h " + dur % 60 + "m");
        int endMin = endMinOf(startMs, dur);

        PendingIntent tap = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, MainActivity.CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Fasting · " + icon + " " + phase)
                .setContentText(durStr + " goal · ends " + fmt(endMin))
                .setUsesChronometer(true)
                .setWhen(startMs)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(ONGOING_ID, n);
    }

    static void cancelOngoing(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(ONGOING_ID);
    }

    static String fmt(int minOfDay) {
        int h = minOfDay / 60, m = minOfDay % 60;
        String ap = h >= 12 ? "PM" : "AM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format("%d:%02d %s", h12, m, ap);
    }
}
