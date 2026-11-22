package com.oldphone.cctv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Satu app, dua mod:
 *  - STREAMER: telefon lama (kamera + pelayan)
 *  - VIEWER  : telefon utama (tonton)
 * UI dibina dalam kod (tiada fail layout) supaya mudah.
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    private static final int REQ_PERM = 11;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // streamer
    private TextView tvStatus, tvUrl, tvCred;
    private Button btnToggle;

    // viewer
    private WebView web;
    private boolean inWeb = false;
    private EditText etHost, etPort, etUser, etPass;

    private static final String[] SIZE_LABELS = {"Rendah 320x240", "Sederhana 640x480", "HD 1280x720", "Full HD 1920x1080"};
    private static final String[] SIZE_VALUES = {"320x240", "640x480", "1280x720", "1920x1080"};
    private static final int[] FPS_VALUES = {2, 5, 10, 15, 20};

    // ------------------------------------------------------------------
    // Kitar hayat
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.get(this);
        route();
    }

    private void route() {
        String m = sp.getString(Prefs.MODE, "");
        if ("streamer".equals(m)) {
            showStreamer();
        } else if ("viewer".equals(m)) {
            showViewer(true);
        } else {
            showChooser();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (inWeb) {
            closeWeb();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------
    // Pembantu UI
    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        return l;
    }

    private TextView tv(String t, int sizeSp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sizeSp);
        v.setTextColor(Color.WHITE);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        return b;
    }

    private Spinner spinner(List<String> items, int sel, AdapterView.OnItemSelectedListener l) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(sel, items.size() - 1)));
        s.setOnItemSelectedListener(l);
        return s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Skrin 1: pilih mod
    // ------------------------------------------------------------------

    private void showChooser() {
        inWeb = false;
        ui.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = column();
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(tv("Old Phone CCTV", 28, true));
        root.addView(tv("Pilih peranan telefon ini. Pilihan disimpan; boleh tukar kemudian.", 15, false));

        Button s = btn("STREAMER\nTelefon lama (hantar video kamera)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putString(Prefs.MODE, "streamer").apply();
                route();
            }
        });
        Button w = btn("VIEWER\nTelefon utama (tonton live)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putString(Prefs.MODE, "viewer").apply();
                route();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
        lp.topMargin = dp(16);
        root.addView(s, lp);
        root.addView(w, lp);
        setContentView(root);
    }

    private void switchMode() {
        new AlertDialog.Builder(this)
                .setTitle("Tukar mod?")
                .setMessage("Jika streamer sedang berjalan, ia akan dihentikan.")
                .setPositiveButton("Tukar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        stopService(new Intent(MainActivity.this, StreamService.class));
                        sp.edit().putString(Prefs.MODE, "").apply();
                        route();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Skrin 2: STREAMER
    // ------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStreamer();
            ui.postDelayed(this, 1500);
        }
    };

    private void showStreamer() {
        inWeb = false;
        ui.removeCallbacksAndMessages(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = column();
        sv.addView(root);

        root.addView(tv("CCTV Streamer", 24, true));
        tvStatus = tv("", 16, false);
        tvUrl = tv("", 18, true);
        tvCred = tv("", 14, false);
        root.addView(tvStatus);
        root.addView(tvUrl);
        root.addView(tvCred);

        btnToggle = btn("MULA", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (StreamService.isRunning()) {
                    stopService(new Intent(MainActivity.this, StreamService.class));
                } else {
                    startStreaming();
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStreamer();
                    }
                }, 400);
            }
        });
        root.addView(btnToggle);

        // Kamera
        root.addView(tv("Kamera", 14, true));
        final List<String> camNames = cameraNames();
        root.addView(spinner(camNames, sp.getInt(Prefs.CAM, 0), new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != sp.getInt(Prefs.CAM, 0)) {
                    sp.edit().putInt(Prefs.CAM, pos).apply();
                    applyToService();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        }));

        // Resolusi
        root.addView(tv("Resolusi", 14, true));
        int selSize = 1;
        String curSize = sp.getString(Prefs.SIZE, "640x480");
        for (int i = 0; i < SIZE_VALUES.length; i++) if (SIZE_VALUES[i].equals(curSize)) selSize = i;
        List<String> sizeItems = new ArrayList<String>();
        Collections.addAll(sizeItems, SIZE_LABELS);
        root.addView(spinner(sizeItems, selSize, new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String val = SIZE_VALUES[pos];
                if (!val.equals(sp.getString(Prefs.SIZE, "640x480"))) {
                    sp.edit().putString(Prefs.SIZE, val).apply();
                    applyToService();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        }));

        // FPS
        root.addView(tv("Kelajuan (FPS maksimum)", 14, true));
        List<String> fpsItems = new ArrayList<String>();
        int selFps = 2;
        int curFps = sp.getInt(Prefs.FPS, 10);
        for (int i = 0; i < FPS_VALUES.length; i++) {
            fpsItems.add(FPS_VALUES[i] + " fps");
            if (FPS_VALUES[i] == curFps) selFps = i;
        }
        root.addView(spinner(fpsItems, selFps, new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (FPS_VALUES[pos] != sp.getInt(Prefs.FPS, 10)) {
                    sp.edit().putInt(Prefs.FPS, FPS_VALUES[pos]).apply();
                    applyToService();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        }));

        // Kualiti
        final TextView tvQ = tv("Kualiti JPEG: " + sp.getInt(Prefs.QUALITY, 60), 14, true);
        root.addView(tvQ);
        SeekBar sb = new SeekBar(this);
        sb.setMax(75); // 20..95
        sb.setProgress(Math.max(0, Math.min(75, sp.getInt(Prefs.QUALITY, 60) - 20)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvQ.setText("Kualiti JPEG: " + (progress + 20));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                sp.edit().putInt(Prefs.QUALITY, s.getProgress() + 20).apply();
                applyToService();
            }
        });
        root.addView(sb);

        // Flash
        CheckBox cbFlash = new CheckBox(this);
        cbFlash.setText("Lampu flash (jika disokong, kamera belakang)");
        cbFlash.setTextColor(Color.WHITE);
        cbFlash.setChecked(sp.getBoolean(Prefs.FLASH, false));
        cbFlash.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.FLASH, checked).apply();
                applyToService();
            }
        });
        root.addView(cbFlash);

        // Autostart
        CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("Mula automatik bila telefon dihidupkan / app dibuka");
        cbAuto.setTextColor(Color.WHITE);
        cbAuto.setChecked(sp.getBoolean(Prefs.AUTOSTART, true));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.AUTOSTART, checked).apply();
            }
        });
        root.addView(cbAuto);

        root.addView(btn("Tukar kata laluan", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePassword();
            }
        }));
        root.addView(btn("Tukar mod (Streamer / Viewer)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode();
            }
        }));
        root.addView(tv("Nota: buka alamat di atas dalam browser telefon utama (rangkaian Wi-Fi yang sama) atau guna mod Viewer.", 13, false));

        setContentView(sv);
        refreshStreamer();
        ui.postDelayed(tick, 1500);

        if (sp.getBoolean(Prefs.AUTOSTART, true) && !StreamService.isRunning()) {
            startStreaming();
        }
    }

    private List<String> cameraNames() {
        List<String> l = new ArrayList<String>();
        try {
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                l.add((ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "Kamera depan" : "Kamera belakang") + " (" + i + ")");
            }
        } catch (Exception ignored) {
        }
        if (l.isEmpty()) l.add("Kamera 0");
        return l;
    }

    private void refreshStreamer() {
        if (tvStatus == null) return;
        StreamService s = StreamService.instance;
        if (s != null) {
            String t = "STATUS: AKTIF - " + s.getMeasuredFps() + " fps, penonton: " + s.clients();
            if (s.cameraError.length() > 0) t += "\n" + s.cameraError;
            if (s.serverError.length() > 0) t += "\n" + s.serverError;
            tvStatus.setText(t);
            btnToggle.setText("HENTI");
        } else {
            tvStatus.setText("STATUS: BERHENTI");
            btnToggle.setText("MULA");
        }
        String ip = NetUtil.localIp(this);
        int port = sp.getInt(Prefs.PORT, 8080);
        tvUrl.setText(ip == null ? "Tiada Wi-Fi" : "http://" + ip + ":" + port);
        tvCred.setText("Pengguna: " + sp.getString(Prefs.USER, "admin") + "\nKata laluan: " + Prefs.password(this));
    }

    private void applyToService() {
        if (StreamService.isRunning()) {
            Intent i = new Intent(this, StreamService.class);
            i.setAction(StreamService.ACTION_APPLY);
            startService(i);
        }
    }

    private void startStreaming() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<String>();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.CAMERA);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_PERM);
                return;
            }
        }
        launchService();
    }

    private void launchService() {
        Intent i = new Intent(this, StreamService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("Gagal mulakan servis: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        boolean camOk = true;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.CAMERA.equals(perms[i]) && results[i] != PackageManager.PERMISSION_GRANTED) {
                camOk = false;
            }
        }
        if (camOk) {
            launchService();
        } else {
            toast("Kebenaran kamera diperlukan.");
        }
    }

    private void changePassword() {
        final EditText et = new EditText(this);
        et.setText(Prefs.password(this));
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Kata laluan baharu (min 4 aksara)")
                .setView(et)
                .setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String p = et.getText().toString().trim();
                        if (p.length() < 4 || p.contains(":")) {
                            toast("Min 4 aksara dan tanpa tanda ':'");
                        } else {
                            sp.edit().putString(Prefs.PASS, p).apply();
                            refreshStreamer();
                        }
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Skrin 3: VIEWER
    // ------------------------------------------------------------------

    private void showViewer(boolean allowAuto) {
        inWeb = false;
        ui.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = column();
        sv.addView(root);

        root.addView(tv("CCTV Viewer", 24, true));
        root.addView(tv("Masukkan IP telefon CCTV (lihat pada skrin Streamer), atau tekan Imbas.", 14, false));

        etHost = new EditText(this);
        etHost.setHint("IP, cth 192.168.1.50");
        etHost.setTextColor(Color.WHITE);
        etHost.setText(sp.getString(Prefs.V_HOST, ""));
        etHost.setSingleLine(true);
        root.addView(etHost);

        etPort = new EditText(this);
        etPort.setHint("Port");
        etPort.setTextColor(Color.WHITE);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(sp.getInt(Prefs.V_PORT, 8080)));
        etPort.setSingleLine(true);
        root.addView(etPort);

        etUser = new EditText(this);
        etUser.setHint("Pengguna");
        etUser.setTextColor(Color.WHITE);
        etUser.setText(sp.getString(Prefs.V_USER, "admin"));
        etUser.setSingleLine(true);
        root.addView(etUser);

        etPass = new EditText(this);
        etPass.setHint("Kata laluan");
        etPass.setTextColor(Color.WHITE);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setText(sp.getString(Prefs.V_PASS, ""));
        etPass.setSingleLine(true);
        root.addView(etPass);

        CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("Sambung automatik bila app dibuka");
        cbAuto.setTextColor(Color.WHITE);
        cbAuto.setChecked(sp.getBoolean(Prefs.V_AUTO, false));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.V_AUTO, checked).apply();
            }
        });
        root.addView(cbAuto);

        root.addView(btn("Imbas rangkaian", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        }));
        root.addView(btn("SAMBUNG", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        }));
        root.addView(btn("Tukar mod (Streamer / Viewer)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putString(Prefs.MODE, "").apply();
                route();
            }
        }));

        setContentView(sv);

        if (allowAuto && sp.getBoolean(Prefs.V_AUTO, false) && sp.getString(Prefs.V_HOST, "").length() > 0) {
            connect();
        }
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(etPort.getText().toString().trim());
            if (p > 0 && p < 65536) return p;
        } catch (Exception ignored) {
        }
        return 8080;
    }

    private void scan() {
        final String ip = NetUtil.localIp(this);
        if (ip == null) {
            toast("Tiada sambungan Wi-Fi.");
            return;
        }
        final String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        final int port = parsePort();
        toast("Mengimbas " + prefix + "1-254 ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                ExecutorService ex = Executors.newFixedThreadPool(40);
                for (int i = 1; i < 255; i++) {
                    final String h = prefix + i;
                    ex.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (NetUtil.isCctv(h, port)) found.add(h);
                        }
                    });
                }
                ex.shutdown();
                try {
                    ex.awaitTermination(40, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showScanResult(found);
                    }
                });
            }
        }).start();
    }

    private void showScanResult(List<String> found) {
        if (isFinishing()) return;
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Tiada dijumpai")
                    .setMessage("Pastikan streamer sedang AKTIF, dan kedua-dua telefon pada Wi-Fi yang sama. Anda juga boleh taip IP secara manual.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final String[] arr = found.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Pilih kamera")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        etHost.setText(arr[which]);
                    }
                })
                .show();
    }

    private void connect() {
        final String host = etHost == null ? sp.getString(Prefs.V_HOST, "") : etHost.getText().toString().trim();
        if (host.length() == 0) {
            toast("Masukkan IP dahulu.");
            return;
        }
        final int port = etPort == null ? sp.getInt(Prefs.V_PORT, 8080) : parsePort();
        final String user = etUser == null ? sp.getString(Prefs.V_USER, "admin") : etUser.getText().toString().trim();
        final String pass = etPass == null ? sp.getString(Prefs.V_PASS, "") : etPass.getText().toString();
        sp.edit().putString(Prefs.V_HOST, host).putInt(Prefs.V_PORT, port)
                .putString(Prefs.V_USER, user).putString(Prefs.V_PASS, pass).apply();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        inWeb = true;

        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setBuiltInZoomControls(false);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient() {
            private int tries = 0;

            @Override
            public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String hst, String realm) {
                if (tries++ < 2) {
                    h.proceed(user, pass);
                } else {
                    h.cancel();
                    toast("Pengguna / kata laluan salah.");
                }
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                String html = "<body style='background:#111;color:#fff;font-family:sans-serif;padding:24px'>"
                        + "<h3>Tidak dapat sambung</h3><p>" + host + ":" + port + "</p>"
                        + "<p>Pastikan streamer AKTIF dan kedua-dua telefon pada Wi-Fi yang sama.</p></body>";
                v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            }
        });
        fl.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button close = new Button(this);
        close.setText("X");
        close.setAlpha(0.6f);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeWeb();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(48));
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(40);
        lp.rightMargin = dp(8);
        fl.addView(close, lp);

        setContentView(fl);
        web.loadUrl("http://" + host + ":" + port + "/");
    }

    private void closeWeb() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        inWeb = false;
        showViewer(false);
    }
}
