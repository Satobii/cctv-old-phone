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
import android.view.MotionEvent;
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
    private static final int REQ_MIC = 12;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // streamer
    private TextView tvStatus, tvUrl, tvCred;
    private Button btnToggle;

    // viewer
    private WebView web;
    private Talker talker;
    private boolean inWeb = false;
    private EditText etHost, etPort, etUser, etPass;

    private static final String[] SIZE_LABELS = {"Low 320x240", "Medium 640x480", "HD 1280x720", "Full HD 1920x1080"};
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
    protected void onResume() {
        super.onResume();
        // Streamer: bila app dibuka, hidupkan kamera sebentar (untuk semak/ubah suai)
        if ("streamer".equals(sp.getString(Prefs.MODE, "")) && StreamService.isRunning()) {
            Intent i = new Intent(this, StreamService.class);
            i.setAction(StreamService.ACTION_WAKE);
            try {
                startService(i);
            } catch (Exception ignored) {
            }
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
        root.addView(tv("Choose the role of this phone. Your choice is saved and can be changed later.", 15, false));

        Button s = btn("STREAMER\nOld phone (sends the camera video)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putString(Prefs.MODE, "streamer").apply();
                route();
            }
        });
        Button w = btn("VIEWER\nMain phone (watch live)", new View.OnClickListener() {
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
                .setTitle("Switch mode?")
                .setMessage("If the streamer is running, it will be stopped.")
                .setPositiveButton("Switch", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        stopService(new Intent(MainActivity.this, StreamService.class));
                        sp.edit().putString(Prefs.MODE, "").apply();
                        route();
                    }
                })
                .setNegativeButton("Cancel", null)
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

        btnToggle = btn("START", new View.OnClickListener() {
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
        root.addView(tv("Camera", 14, true));
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
        root.addView(tv("Resolution", 14, true));
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
        root.addView(tv("Speed (max FPS)", 14, true));
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
        final TextView tvQ = tv("JPEG quality: " + sp.getInt(Prefs.QUALITY, 60), 14, true);
        root.addView(tvQ);
        SeekBar sb = new SeekBar(this);
        sb.setMax(75); // 20..95
        sb.setProgress(Math.max(0, Math.min(75, sp.getInt(Prefs.QUALITY, 60) - 20)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvQ.setText("JPEG quality: " + (progress + 20));
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
        cbFlash.setText("Flash light (if supported, rear camera)");
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

        // Mod jimat bateri
        CheckBox cbEco = new CheckBox(this);
        cbEco.setText("Battery saver: camera runs only while someone is watching");
        cbEco.setTextColor(Color.WHITE);
        cbEco.setChecked(sp.getBoolean(Prefs.ECO, true));
        cbEco.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.ECO, checked).apply();
                applyToService();
            }
        });
        root.addView(cbEco);

        // Autostart
        CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("Start automatically on boot / when the app opens");
        cbAuto.setTextColor(Color.WHITE);
        cbAuto.setChecked(sp.getBoolean(Prefs.AUTOSTART, true));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.AUTOSTART, checked).apply();
            }
        });
        root.addView(cbAuto);

        if (Build.VERSION.SDK_INT >= 23) {
            root.addView(btn("Allow background running (ignore battery optimization)", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        toast("Open Settings > Battery and allow this app to run in the background.");
                    }
                }
            }));
        }
        root.addView(btn("Change password", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePassword();
            }
        }));
        root.addView(btn("Switch mode (Streamer / Viewer)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode();
            }
        }));
        root.addView(tv("Note: open the address above in the browser of your main phone (same Wi-Fi network) or use Viewer mode.", 13, false));

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
                l.add((ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "Front camera" : "Rear camera") + " (" + i + ")");
            }
        } catch (Exception ignored) {
        }
        if (l.isEmpty()) l.add("Camera 0");
        return l;
    }

    private void refreshStreamer() {
        if (tvStatus == null) return;
        StreamService s = StreamService.instance;
        if (s != null) {
            String t = s.isCameraActive()
                    ? "STATUS: CAMERA ON - " + Math.round(s.getMeasuredFps()) + " fps, viewers: " + s.clients()
                    : "STATUS: READY (camera asleep, saving battery)";
            if (s.cameraError.length() > 0) t += "\n" + s.cameraError;
            if (s.serverError.length() > 0) t += "\n" + s.serverError;
            tvStatus.setText(t);
            btnToggle.setText("STOP");
        } else {
            tvStatus.setText("STATUS: STOPPED");
            btnToggle.setText("START");
        }
        String ip = NetUtil.localIp(this);
        int port = sp.getInt(Prefs.PORT, 8080);
        tvUrl.setText(ip == null ? "No Wi-Fi" : "http://" + ip + ":" + port);
        tvCred.setText("User: " + sp.getString(Prefs.USER, "admin") + "\nPassword: " + Prefs.password(this));
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
        i.setAction(StreamService.ACTION_WAKE);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("Failed to start service: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "Microphone allowed. Hold the TALK button to speak." : "Microphone permission is required to talk.");
            return;
        }
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
            toast("Camera permission is required.");
        }
    }

    private void changePassword() {
        final EditText et = new EditText(this);
        et.setText(Prefs.password(this));
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("New password (min 4 characters)")
                .setView(et)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String p = et.getText().toString().trim();
                        if (p.length() < 4 || p.contains(":")) {
                            toast("Min 4 characters, no ':' allowed");
                        } else {
                            sp.edit().putString(Prefs.PASS, p).apply();
                            refreshStreamer();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
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
        root.addView(tv("Enter the IP of the CCTV phone (shown on the Streamer screen), or tap Scan.", 14, false));

        etHost = new EditText(this);
        etHost.setHint("IP, e.g. 192.168.1.50");
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
        etUser.setHint("User");
        etUser.setTextColor(Color.WHITE);
        etUser.setText(sp.getString(Prefs.V_USER, "admin"));
        etUser.setSingleLine(true);
        root.addView(etUser);

        etPass = new EditText(this);
        etPass.setHint("Password");
        etPass.setTextColor(Color.WHITE);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setText(sp.getString(Prefs.V_PASS, ""));
        etPass.setSingleLine(true);
        root.addView(etPass);

        CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("Connect automatically when the app opens");
        cbAuto.setTextColor(Color.WHITE);
        cbAuto.setChecked(sp.getBoolean(Prefs.V_AUTO, false));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(Prefs.V_AUTO, checked).apply();
            }
        });
        root.addView(cbAuto);

        root.addView(btn("Scan network", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        }));
        root.addView(btn("CONNECT", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        }));
        root.addView(btn("Switch mode (Streamer / Viewer)", new View.OnClickListener() {
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
            toast("No Wi-Fi connection.");
            return;
        }
        final String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        final int port = parsePort();
        toast("Scanning " + prefix + "1-254 ...");
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
                    .setTitle("Nothing found")
                    .setMessage("Make sure the streamer is RUNNING and both phones are on the same Wi-Fi. You can also type the IP manually.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final String[] arr = found.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Choose camera")
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
            toast("Enter an IP first.");
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
                    toast("Wrong user / password.");
                }
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                String html = "<body style='background:#111;color:#fff;font-family:sans-serif;padding:24px'>"
                        + "<h3>Cannot connect</h3><p>" + host + ":" + port + "</p>"
                        + "<p>Make sure the streamer is RUNNING and both phones are on the same Wi-Fi.</p></body>";
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

        // Push-to-talk: hold to speak, the streamer phone plays your voice
        talker = new Talker(new Talker.Callback() {
            @Override
            public void onError(String message) {
                toast(message);
            }
        });
        final Button talk = new Button(this);
        talk.setText("HOLD TO TALK");
        talk.setAlpha(0.85f);
        talk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    if (Build.VERSION.SDK_INT >= 23
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        return true;
                    }
                    talk.setText("TALKING...");
                    talker.start(host, port, user, pass);
                    return true;
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    talker.stop();
                    talk.setText("HOLD TO TALK");
                    return true;
                }
                return true;
            }
        });
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(170), dp(60));
        tp.gravity = Gravity.TOP | Gravity.START;
        tp.topMargin = dp(40);
        tp.leftMargin = dp(8);
        fl.addView(talk, tp);

        setContentView(fl);
        web.loadUrl("http://" + host + ":" + port + "/");
    }

    private void closeWeb() {
        if (talker != null) {
            talker.stop();
            talker = null;
        }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        inWeb = false;
        showViewer(false);
    }
}
