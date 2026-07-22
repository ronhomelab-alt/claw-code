package com.vitafast.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {

    static final String CHANNEL_ID = "vitafast_fasts";
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

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

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Fasting alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notifies you when a fast begins and ends");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    class Bridge {
        @JavascriptInterface
        public void setSchedule(int startMin, int endMin, boolean enabled, boolean notify) {
            Scheduler.saveAndReschedule(MainActivity.this, startMin, endMin, enabled, notify);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 1));
            }
        }
    }
}
