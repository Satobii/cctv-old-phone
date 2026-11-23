package com.oldphone.cctv;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Semua tetapan disimpan di sini (SharedPreferences). */
public final class Prefs {
    private Prefs() {}

    public static final String NAME = "cctv";

    // Umum
    public static final String MODE = "mode";            // "", "streamer", "viewer"

    // Streamer
    public static final String PORT = "port";            // default 8080
    public static final String USER = "user";            // default admin
    public static final String PASS = "pass";            // dijana rawak kali pertama
    public static final String CAM = "cam";              // id kamera
    public static final String SIZE = "size";            // contoh "640x480"
    public static final String QUALITY = "quality";      // 10..95
    public static final String FPS = "fps";              // 1..30
    public static final String FLASH = "flash";          // boolean
    public static final String ROT = "rot";              // -1 auto, 0/90/180/270
    public static final String MIRROR = "mirror";        // boolean
    public static final String AUTOSTART = "autostart";  // boolean
    public static final String ECO = "eco";              // boolean, default true: kamera hidup bila ada penonton

    // Viewer
    public static final String V_HOST = "v_host";
    public static final String V_PORT = "v_port";
    public static final String V_USER = "v_user";
    public static final String V_PASS = "v_pass";
    public static final String V_AUTO = "v_auto";

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Kata laluan streamer. Dijana rawak (8 aksara) jika belum ada. */
    public static String password(Context c) {
        SharedPreferences sp = get(c);
        String s = sp.getString(PASS, null);
        if (s == null || s.length() < 4) {
            s = randomPassword();
            sp.edit().putString(PASS, s).apply();
        }
        return s;
    }

    private static String randomPassword() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
