package com.oldphone.cctv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Servis latar depan.
 *
 * Mod jimat bateri (default): servis + pelayan HTTP sentiasa hidup (hampir tiada kos bateri),
 * tetapi KAMERA hanya dibuka bila ada penonton / app dibuka, dan ditutup ~20 saat selepas
 * penonton terakhir pergi. Semasa kamera tidur, wake lock dan Wi-Fi high-perf dilepaskan.
 */
@SuppressWarnings("deprecation")
public class StreamService extends Service {

    static final String ACTION_APPLY = "com.oldphone.cctv.APPLY";
    static final String ACTION_WAKE = "com.oldphone.cctv.WAKE";
    private static final String CHANNEL = "cctv";
    private static final long LINGER_MS = 20000;

    static volatile StreamService instance;

    static boolean isRunning() {
        return instance != null;
    }

    static final class Frame {
        final byte[] data;
        final long seq;

        Frame(byte[] d, long s) {
            data = d;
            seq = s;
        }
    }

    // ---- keadaan servis ----
    private boolean started = false;
    private volatile boolean running = false;
    private long startTime;

    private HandlerThread camThread;
    private Handler camHandler;
    private Camera camera;
    private SurfaceTexture dummyTexture;
    private MjpegServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiIdleLock;
    private WifiManager.WifiLock wifiPerfLock;

    private volatile boolean cameraActive = false;
    private volatile long wakeUntil = 0;

    // ---- stor frame ----
    private final Object frameLock = new Object();
    private byte[] latestJpeg;
    private long frameSeq = 0;
    private volatile long lastFrameAt = 0;
    private volatile long lastPreviewTime = 0;
    private long lastEncodeTime = 0;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(64 * 1024);

    // ---- tetapan (dibaca dari Prefs) ----
    private volatile int camId = 0;
    private volatile int wantW = 640, wantH = 480;
    private volatile String sizeStr = "640x480";
    private volatile int quality = 60;
    private volatile int fps = 10;
    private volatile boolean flashOn = false;
    private volatile int rotSetting = -1;
    private volatile boolean mirror = false;
    private volatile boolean eco = true;

    // ---- maklumat untuk status ----
    private volatile int curW = 0, curH = 0;
    private volatile int sensorOrientation = 90;
    private volatile boolean flashSupported = false;
    private volatile String[] supportedSizes = new String[0];
    volatile String cameraError = "";
    volatile String serverError = "";
    private volatile float measuredFps = 0f;
    private long fpsStart = 0;
    private int fpsCount = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!started) {
            started = true;
            running = true;
            instance = this;
            startTime = SystemClock.elapsedRealtime();

            if (!goForeground()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireIdleLock();
            loadSettings();

            camThread = new HandlerThread("camera", Process.THREAD_PRIORITY_DISPLAY);
            camThread.start();
            camHandler = new Handler(camThread.getLooper());

            server = new MjpegServer(this, Prefs.get(this).getInt(Prefs.PORT, 8080));
            server.start();

            camHandler.post(checkRunnable);
            camHandler.postDelayed(watchdog, 10000);
        } else if (ACTION_APPLY.equals(action)) {
            applyFromPrefs();
        }
        if (ACTION_WAKE.equals(action)) {
            touch(30000);
        }
        return START_STICKY;
    }

    /** App dibuang dari Recents: minta Android hidupkan semula servis ini. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent i = new Intent(this, StreamService.class);
            PendingIntent pi;
            if (Build.VERSION.SDK_INT >= 26) {
                pi = PendingIntent.getForegroundService(this, 1, i,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                pi = PendingIntent.getService(this, 1, i, PendingIntent.FLAG_ONE_SHOT);
            }
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pi);
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (server != null) {
            server.shutdown();
            server = null;
        }
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (camHandler != null) {
            camHandler.removeCallbacksAndMessages(null);
            camHandler.post(new Runnable() {
                @Override
                public void run() {
                    cameraActive = false;
                    closeCamera();
                    if (camThread != null) camThread.quitSafely();
                }
            });
        }
        releasePerfLocks();
        try {
            if (wifiIdleLock != null && wifiIdleLock.isHeld()) wifiIdleLock.release();
        } catch (Exception ignored) {
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Notifikasi / kunci kuasa
    // ------------------------------------------------------------------

    private boolean goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(1, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "CCTV", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        b.setContentTitle("CCTV ready")
                .setContentText("Waiting for viewers (camera starts when someone watches)")
                .setSmallIcon(R.drawable.ic_stat_cctv)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    /** Kunci Wi-Fi ringan: kekalkan Wi-Fi hidup walaupun skrin mati. */
    private void acquireIdleLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiIdleLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "cctv:idle");
            wifiIdleLock.setReferenceCounted(false);
            wifiIdleLock.acquire();
        } catch (Exception ignored) {
        }
    }

    /** Semasa kamera hidup: CPU jaga + Wi-Fi tanpa penjimatan kuasa (kurangkan lag). */
    private void acquirePerfLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:stream");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
        try {
            if (wifiPerfLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiPerfLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cctv:perf");
                wifiPerfLock.setReferenceCounted(false);
            }
            if (!wifiPerfLock.isHeld()) wifiPerfLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releasePerfLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiPerfLock != null && wifiPerfLock.isHeld()) wifiPerfLock.release();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Tetapan
    // ------------------------------------------------------------------

    private void loadSettings() {
        SharedPreferences sp = Prefs.get(this);
        camId = sp.getInt(Prefs.CAM, 0);
        setSize(sp.getString(Prefs.SIZE, "640x480"));
        quality = clamp(sp.getInt(Prefs.QUALITY, 60), 10, 95);
        fps = clamp(sp.getInt(Prefs.FPS, 10), 1, 30);
        flashOn = sp.getBoolean(Prefs.FLASH, false);
        rotSetting = sp.getInt(Prefs.ROT, -1);
        mirror = sp.getBoolean(Prefs.MIRROR, false);
        eco = sp.getBoolean(Prefs.ECO, true);
    }

    private void setSize(String s) {
        try {
            String[] p = s.split("x");
            int w = Integer.parseInt(p[0]);
            int h = Integer.parseInt(p[1]);
            if (w >= 160 && h >= 120) {
                wantW = w;
                wantH = h;
                sizeStr = s;
                return;
            }
        } catch (Exception ignored) {
        }
        wantW = 640;
        wantH = 480;
        sizeStr = "640x480";
    }

    /** Baca semula tetapan; mula semula kamera hanya jika kamera/saiz berubah. */
    void applyFromPrefs() {
        SharedPreferences sp = Prefs.get(this);
        int newCam = sp.getInt(Prefs.CAM, 0);
        String newSize = sp.getString(Prefs.SIZE, "640x480");
        boolean restart = (newCam != camId) || !newSize.equals(sizeStr);

        camId = newCam;
        setSize(newSize);
        quality = clamp(sp.getInt(Prefs.QUALITY, 60), 10, 95);
        fps = clamp(sp.getInt(Prefs.FPS, 10), 1, 30);
        flashOn = sp.getBoolean(Prefs.FLASH, false);
        rotSetting = sp.getInt(Prefs.ROT, -1);
        mirror = sp.getBoolean(Prefs.MIRROR, false);
        eco = sp.getBoolean(Prefs.ECO, true);

        if (camHandler == null) return;
        final boolean r = restart;
        camHandler.post(new Runnable() {
            @Override
            public void run() {
                checkActive();
                if (!cameraActive) return;
                if (r) {
                    openCamera();
                } else {
                    applyFlash();
                }
            }
        });
    }

    /** Dipanggil oleh pelayan HTTP (/api/set). */
    void updateSettings(Map<String, String> q) {
        SharedPreferences.Editor ed = Prefs.get(this).edit();
        try {
            if (q.containsKey("cam")) ed.putInt(Prefs.CAM, Math.max(0, Integer.parseInt(q.get("cam"))));
            if (q.containsKey("size") && q.get("size").matches("\\d{3,4}x\\d{3,4}")) {
                ed.putString(Prefs.SIZE, q.get("size"));
            }
            if (q.containsKey("quality")) ed.putInt(Prefs.QUALITY, clamp(Integer.parseInt(q.get("quality")), 10, 95));
            if (q.containsKey("fps")) ed.putInt(Prefs.FPS, clamp(Integer.parseInt(q.get("fps")), 1, 30));
            if (q.containsKey("flash")) ed.putBoolean(Prefs.FLASH, "1".equals(q.get("flash")));
            if (q.containsKey("mirror")) ed.putBoolean(Prefs.MIRROR, "1".equals(q.get("mirror")));
            if (q.containsKey("rot")) {
                int r = Integer.parseInt(q.get("rot"));
                if (r == -1 || r == 0 || r == 90 || r == 180 || r == 270) ed.putInt(Prefs.ROT, r);
            }
        } catch (NumberFormatException ignored) {
        }
        ed.apply();
        applyFromPrefs();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------
    // Hidup / tidur kamera (semua pada camThread)
    // ------------------------------------------------------------------

    /** Ada aktiviti (penonton / app dibuka): pastikan kamera hidup sekurang-kurangnya ms lagi. */
    void touch(long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        if (until > wakeUntil) wakeUntil = until;
        Handler h = camHandler;
        if (h != null && !cameraActive) {
            h.removeCallbacks(checkRunnable);
            h.post(checkRunnable);
        }
    }

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            checkActive();
            camHandler.removeCallbacks(this);
            camHandler.postDelayed(this, 3000);
        }
    };

    private void checkActive() {
        boolean want = !eco || clients() > 0 || SystemClock.elapsedRealtime() < wakeUntil;
        if (want && !cameraActive) {
            cameraActive = true;
            acquirePerfLocks();
            openCamera();
        } else if (!want && cameraActive) {
            cameraActive = false;
            camHandler.removeCallbacks(openRunnable);
            closeCamera();
            releasePerfLocks();
            cameraError = "";
            measuredFps = 0f;
        }
    }

    // ------------------------------------------------------------------
    // Kamera
    // ------------------------------------------------------------------

    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            if (cameraActive) openCamera();
        }
    };

    private void scheduleOpen(long delayMs) {
        if (camHandler == null) return;
        camHandler.removeCallbacks(openRunnable);
        camHandler.postDelayed(openRunnable, delayMs);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (cameraActive && now - lastPreviewTime > 15000) {
                cameraError = "No frames, restarting camera";
                openCamera();
            }
            camHandler.postDelayed(this, 10000);
        }
    };

    private final Camera.ErrorCallback errCb = new Camera.ErrorCallback() {
        @Override
        public void onError(int error, Camera c) {
            cameraError = "Camera error (" + error + "), retrying";
            scheduleOpen(2000);
        }
    };

    private final Camera.PreviewCallback previewCb = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            long now = SystemClock.elapsedRealtime();
            lastPreviewTime = now;
            try {
                MjpegServer sv = server;
                int interval = (sv != null && sv.clientCount() > 0) ? 1000 / Math.max(1, fps) : 1000;
                if (now - lastEncodeTime >= interval - 3) {
                    lastEncodeTime = now;
                    int w = curW, h = curH;
                    YuvImage yuv = new YuvImage(data, ImageFormat.NV21, w, h, null);
                    jpegOut.reset();
                    yuv.compressToJpeg(new Rect(0, 0, w, h), quality, jpegOut);
                    byte[] jpg = jpegOut.toByteArray();
                    synchronized (frameLock) {
                        latestJpeg = jpg;
                        frameSeq++;
                        lastFrameAt = now;
                        frameLock.notifyAll();
                    }
                    countFps(now);
                }
            } catch (Exception ignored) {
            }
            try {
                cam.addCallbackBuffer(data);
            } catch (Exception ignored) {
            }
        }
    };

    private void countFps(long now) {
        if (fpsStart == 0) fpsStart = now;
        fpsCount++;
        if (now - fpsStart >= 2000) {
            measuredFps = fpsCount * 1000f / (now - fpsStart);
            fpsCount = 0;
            fpsStart = now;
        }
    }

    private void openCamera() {
        if (!running || !cameraActive) return;
        closeCamera();
        lastPreviewTime = SystemClock.elapsedRealtime();
        try {
            int n = Camera.getNumberOfCameras();
            if (n <= 0) {
                cameraError = "No camera found";
                scheduleOpen(5000);
                return;
            }
            int id = camId;
            if (id < 0 || id >= n) id = 0;

            Camera c = Camera.open(id);
            camera = c;

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            sensorOrientation = info.orientation;

            Camera.Parameters p = c.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            supportedSizes = buildSizeList(sizes);
            List<String> fm = p.getSupportedFlashModes();
            flashSupported = fm != null && fm.contains(Camera.Parameters.FLASH_MODE_TORCH);

            Camera.Size best = pickSize(sizes, wantW, wantH);
            try {
                p.setPreviewSize(best.width, best.height);
                p.setPreviewFormat(ImageFormat.NV21);
                c.setParameters(p);
            } catch (Exception ignored) {
            }

            // Kekalkan fps tinggi (elak kamera turun ke 7 fps dalam cahaya malap) + mod video
            p = c.getParameters();
            try {
                int[] r = pickFpsRange(p.getSupportedPreviewFpsRange());
                if (r != null) p.setPreviewFpsRange(r[0], r[1]);
                p.setRecordingHint(true);
                c.setParameters(p);
            } catch (Exception ignored) {
            }

            p = c.getParameters();
            List<String> fo = p.getSupportedFocusModes();
            if (fo != null && fo.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                try {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    c.setParameters(p);
                } catch (Exception ignored) {
                }
            }

            Camera.Size ps = c.getParameters().getPreviewSize();
            curW = ps.width;
            curH = ps.height;

            int bufSize = curW * curH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < 3; i++) c.addCallbackBuffer(new byte[bufSize]);
            c.setPreviewCallbackWithBuffer(previewCb);
            c.setErrorCallback(errCb);

            dummyTexture = new SurfaceTexture(10);
            c.setPreviewTexture(dummyTexture);
            c.startPreview();

            cameraError = "";
            applyFlash();
        } catch (Exception e) {
            cameraError = "Failed to open camera: " + e.getMessage();
            closeCamera();
            scheduleOpen(5000);
        }
    }

    private void closeCamera() {
        Camera c = camera;
        camera = null;
        if (c != null) {
            try {
                c.setPreviewCallbackWithBuffer(null);
                c.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
        }
        if (dummyTexture != null) {
            try {
                dummyTexture.release();
            } catch (Exception ignored) {
            }
            dummyTexture = null;
        }
    }

    private void applyFlash() {
        Camera c = camera;
        if (c == null) return;
        try {
            Camera.Parameters p = c.getParameters();
            List<String> fm = p.getSupportedFlashModes();
            if (fm != null && fm.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                p.setFlashMode(flashOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                c.setParameters(p);
            }
        } catch (Exception ignored) {
        }
    }

    /** Pilih julat fps dengan maksimum tertinggi (<=30), minimum paling hampir 15 tanpa melebihinya. */
    private static int[] pickFpsRange(List<int[]> ranges) {
        if (ranges == null || ranges.isEmpty()) return null;
        int maxTop = 0;
        for (int[] r : ranges) if (r[1] <= 30000 && r[1] > maxTop) maxTop = r[1];
        if (maxTop == 0) return null;
        int[] best = null;
        for (int[] r : ranges) {
            if (r[1] != maxTop) continue;
            if (best == null) {
                best = r;
            } else if (r[0] <= 15000 && (best[0] > 15000 || r[0] > best[0])) {
                best = r;
            } else if (best[0] > 15000 && r[0] < best[0]) {
                best = r;
            }
        }
        return best;
    }

    private static Camera.Size pickSize(List<Camera.Size> list, int w, int h) {
        Camera.Size best = list.get(0);
        long bestScore = Long.MAX_VALUE;
        double want = (double) w / h;
        for (Camera.Size s : list) {
            long score = Math.abs((long) s.width * s.height - (long) w * h);
            score += (long) (Math.abs((double) s.width / s.height - want) * 1000000d);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static String[] buildSizeList(List<Camera.Size> sizes) {
        List<Camera.Size> l = new ArrayList<Camera.Size>(sizes);
        Collections.sort(l, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size a, Camera.Size b) {
                return b.width * b.height - a.width * a.height;
            }
        });
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (Camera.Size s : l) {
            if (s.width <= 1920) set.add(s.width + "x" + s.height);
        }
        return set.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // API untuk pelayan HTTP / UI
    // ------------------------------------------------------------------

    /** Tunggu frame yang lebih baharu daripada afterSeq. Null jika tamat masa. */
    Frame getFrame(long afterSeq, long timeoutMs) throws InterruptedException {
        synchronized (frameLock) {
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            while (frameSeq <= afterSeq && running) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                frameLock.wait(left);
            }
            if (latestJpeg == null || frameSeq <= afterSeq) return null;
            return new Frame(latestJpeg, frameSeq);
        }
    }

    long currentSeq() {
        synchronized (frameLock) {
            return frameSeq;
        }
    }

    long frameAgeMs() {
        return SystemClock.elapsedRealtime() - lastFrameAt;
    }

    boolean isRunningFlag() {
        return running;
    }

    boolean isCameraActive() {
        return cameraActive;
    }

    int clients() {
        MjpegServer s = server;
        return s == null ? 0 : s.clientCount();
    }

    float getMeasuredFps() {
        return measuredFps;
    }

    String statusJson() {
        try {
            SharedPreferences sp = Prefs.get(this);
            JSONObject o = new JSONObject();
            o.put("ok", true);

            JSONArray cams = new JSONArray();
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                JSONObject c = new JSONObject();
                c.put("id", i);
                c.put("name", ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "Front" : "Rear");
                cams.put(c);
            }
            o.put("cameras", cams);
            o.put("cam", sp.getInt(Prefs.CAM, 0));

            JSONArray sz = new JSONArray();
            for (String s : supportedSizes) sz.put(s);
            o.put("sizes", sz);
            o.put("size", sizeStr);
            o.put("actual", curW + "x" + curH);

            o.put("quality", quality);
            o.put("fps", fps);
            o.put("flash", flashOn);
            o.put("flashSupported", flashSupported);
            o.put("rot", rotSetting >= 0 ? rotSetting : sensorOrientation);
            o.put("rotAuto", rotSetting < 0);
            o.put("mirror", mirror);
            o.put("measuredFps", Math.round(measuredFps * 10f) / 10f);
            o.put("clients", clients());
            o.put("uptime", (SystemClock.elapsedRealtime() - startTime) / 1000);
            o.put("error", cameraError);
            o.put("eco", eco);
            o.put("active", cameraActive);

            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                o.put("battery", scale > 0 ? level * 100 / scale : -1);
                o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
