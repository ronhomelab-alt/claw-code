package com.vitafast.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String CH_ALERT = "vitafast_fasts";
    static final String CH_ONGOING = "vitafast_ongoing";
    static final int REQ_IMPORT = 42;
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
        public void exportData(String json) {
            runOnUiThread(() -> {
                String name = "vitafast-backup-"
                        + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                        cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                        cv.put(MediaStore.Downloads.IS_PENDING, 1);
                        Uri item = getContentResolver().insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        try (OutputStream os = getContentResolver().openOutputStream(item)) {
                            os.write(json.getBytes("UTF-8"));
                        }
                        cv.clear();
                        cv.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(item, cv, null, null);
                        toast("Backup saved to Downloads/" + name);
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        File f = new File(dir, name);
                        try (FileOutputStream fo = new FileOutputStream(f)) {
                            fo.write(json.getBytes("UTF-8"));
                        }
                        toast("Backup saved: " + f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    toast("Export failed: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void importData() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/json", "text/plain", "text/*"});
                try { startActivityForResult(i, REQ_IMPORT); }
                catch (Exception e) { toast("No file picker available"); }
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = getContentResolver().openInputStream(data.getData());
                     BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                String quoted = JSONObject.quote(sb.toString());
                if (web != null) {
                    web.evaluateJavascript(
                            "window.vfApplyImport && window.vfApplyImport(" + quoted + ")", null);
                }
            } catch (Exception e) {
                toast("Import failed: " + e.getMessage());
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void openAppDetails() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }
}
