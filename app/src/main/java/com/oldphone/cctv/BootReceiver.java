package com.oldphone.cctv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Bila telefon dihidupkan: jika mod Streamer + mula automatik, hidupkan servis kamera. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a);
        if (!boot) return;

        SharedPreferences sp = Prefs.get(c);
        if ("streamer".equals(sp.getString(Prefs.MODE, "")) && sp.getBoolean(Prefs.AUTOSTART, true)) {
            Intent s = new Intent(c, StreamService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    c.startForegroundService(s);
                } else {
                    c.startService(s);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
