package com.vitafast.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

public class MainActivity extends Activity {

    static final String CH_ALERT = "vitafast_fasts";
    static final String CH_ONGOING = "vitafast_ongoing";
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createChannels();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage for schedule + history
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF071518);
        web.addJavascriptInterface(new Bridge(), "VitaFastNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "Fasting alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Fast start, end, and phase-transition alerts");
        nm.createNotificationChannel(alert);

        NotificationChannel ongoing = new NotificationChannel(
                CH_ONGOING, "Live fasting status", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("The ongoing notification shown while a fast is in progress");
        ongoing.setShowBadge(false);
        ongoing.setSound(null, null);
        nm.createNotificationChannel(ongoing);
    }

    class Bridge {
        @JavascriptInterface
        public void setConfig(String json) {
            Scheduler.saveConfig(MainActivity.this, json);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 1));
            }
        }

        @JavascriptInterface
        public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT >= 31) {
                runOnUiThread(() -> {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); } catch (Exception e) { openAppDetails(); }
                });
            } else openAppDetails();
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        public void openBatterySettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); }
                catch (Exception e) {
                    Intent l = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(l); } catch (Exception ex) { openAppDetails(); }
                }
            });
        }

        @JavascriptInterface
        public String getPermStatus() {
            boolean notif = getSystemService(NotificationManager.class).areNotificationsEnabled();
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                exact = getSystemService(AlarmManager.class).canScheduleExactAlarms();
            }
            boolean battery = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) battery = pm.isIgnoringBatteryOptimizations(getPackageName());
            JSONObject o = new JSONObject();
            try {
                o.put("notif", notif);
                o.put("exact", exact);
                o.put("battery", battery);
            } catch (Exception ignored) {}
            return o.toString();
        }
    }

    private void openAppDetails() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }
}
