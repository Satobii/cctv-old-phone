package com.oldphone.cctv;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.PowerManager;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pelayan HTTP kecil (tanpa library). Semua laluan (kecuali /ping) perlukan login Basic.
 *
 *   /              halaman web penonton (assets/viewer.html)
 *   /stream        MJPEG (multipart/x-mixed-replace)
 *   /snapshot      satu gambar JPEG
 *   /api/status    JSON status
 *   /api/set       POST: ubah tetapan (cam, size, quality, fps, flash, rot, mirror)
 *   /ping          tanpa login, untuk imbasan rangkaian
 */
public class MjpegServer extends Thread {
    private static final int MAX_CONN = 10;

    private final StreamService svc;
    private final int port;
    private volatile boolean running = true;
    private ServerSocket ss;
    private final AtomicInteger streams = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean talking = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private byte[] pageBytes;

    MjpegServer(StreamService s, int port) {
        super("mjpeg-server");
        this.svc = s;
        this.port = port;
    }

    int clientCount() {
        return streams.get();
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            svc.serverError = "Port " + port + " cannot be opened: " + e.getMessage();
            return;
        }
        while (running) {
            try {
                final Socket s = ss.accept();
                if (active.get() >= MAX_CONN) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                active.incrementAndGet();
                open.add(s);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(s);
                        } finally {
                            active.decrementAndGet();
                            open.remove(s);
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                });
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    void shutdown() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.length() == 0) return;
            Map<String, String> h = new HashMap<String, String>();
            for (int i = 0; i < 64; i++) {
                String l = readLine(in);
                if (l == null || l.length() == 0) break;
                int c = l.indexOf(':');
                if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.US), l.substring(c + 1).trim());
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            if (path.equals("/ping")) {
                send(out, 200, "OK", "text/plain", "OLDPHONE-CCTV".getBytes("UTF-8"), "X-App: OLDPHONE-CCTV\r\n");
                return;
            }

            if (!authorized(h.get("authorization"))) {
                try {
                    Thread.sleep(700); // perlahankan tekaan kata laluan
                } catch (InterruptedException ignored) {
                }
                send(out, 401, "Unauthorized", "text/plain", "Login required".getBytes("UTF-8"),
                        "WWW-Authenticate: Basic realm=\"Old Phone CCTV\"\r\n");
                return;
            }

            if (!path.equals("/talk")) svc.touch(20000); // valid request: keep the camera awake

            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "OK", "text/html; charset=utf-8", page(), null);
            } else if (path.equals("/stream")) {
                stream(s, out);
            } else if (path.equals("/snapshot") || path.equals("/snapshot.jpg")) {
                // Jika frame lama (kamera baru bangun), tunggu frame segar
                StreamService.Frame f = svc.frameAgeMs() < 1500
                        ? svc.getFrame(0, 3000)
                        : svc.getFrame(svc.currentSeq(), 6000);
                if (f == null || svc.frameAgeMs() > 8000) {
                    send(out, 503, "Service Unavailable", "text/plain", "Camera not ready".getBytes("UTF-8"), null);
                } else {
                    send(out, 200, "OK", "image/jpeg", f.data, null);
                }
            } else if (path.equals("/talk")) {
                talk(s, in, out);
            } else if (path.equals("/api/status")) {
                send(out, 200, "OK", "application/json", svc.statusJson().getBytes("UTF-8"), null);
            } else if (path.equals("/api/set")) {
                if (!"POST".equals(method)) {
                    send(out, 405, "Method Not Allowed", "text/plain", "Use POST".getBytes("UTF-8"), null);
                } else {
                    svc.updateSettings(parseQuery(query));
                    send(out, 200, "OK", "application/json", svc.statusJson().getBytes("UTF-8"), null);
                }
            } else {
                send(out, 404, "Not Found", "text/plain", "Not found".getBytes("UTF-8"), null);
            }
        } catch (Exception ignored) {
            // klien putus sambungan, tamat masa, dsb.
        }
    }

    private void stream(Socket s, OutputStream out) throws IOException, InterruptedException {
        streams.incrementAndGet();
        try {
            // Buffer hantar kecil: elak frame beratur dalam kernel (punca utama delay).
            try {
                s.setSendBufferSize(64 * 1024);
            } catch (Exception ignored) {
            }
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("ISO-8859-1"));
            out.flush();
            // Jika frame tersimpan sudah lama (kamera baru bangun), jangan hantar gambar lapuk
            long seq = svc.frameAgeMs() > 1500 ? svc.currentSeq() : 0;
            byte[] buf = new byte[0];
            while (running && svc.isRunningFlag()) {
                StreamService.Frame f = svc.getFrame(seq, 5000);
                if (f == null) continue;
                seq = f.seq;
                byte[] hd = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.data.length + "\r\n\r\n")
                        .getBytes("ISO-8859-1");
                int total = hd.length + f.data.length + 2;
                if (buf.length < total) buf = new byte[total + 16 * 1024];
                System.arraycopy(hd, 0, buf, 0, hd.length);
                System.arraycopy(f.data, 0, buf, hd.length, f.data.length);
                buf[total - 2] = '\r';
                buf[total - 1] = '\n';
                out.write(buf, 0, total); // satu tulisan sahaja per frame
                out.flush();
            }
        } finally {
            streams.decrementAndGet();
        }
    }

    /**
     * Push-to-talk: the viewer sends raw PCM (16 kHz, mono, 16-bit) until it closes the connection.
     * It is played through the speaker of this phone. Only one talker at a time.
     */
    @SuppressWarnings("deprecation")
    private void talk(Socket s, InputStream in, OutputStream out) throws IOException {
        if (!talking.compareAndSet(false, true)) {
            send(out, 409, "Conflict", "text/plain", "Someone else is talking".getBytes("UTF-8"), null);
            return;
        }
        PowerManager.WakeLock wl = null;
        AudioTrack at = null;
        AudioManager am = null;
        int oldVol = -1;
        try {
            s.setSoTimeout(5000);
            try {
                PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:talk");
                wl.setReferenceCounted(false);
                wl.acquire(10 * 60 * 1000L);
            } catch (Exception ignored) {
            }
            try {
                am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
                oldVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            } catch (Exception ignored) {
            }
            int min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            at = new AudioTrack(AudioManager.STREAM_MUSIC, 16000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8000), AudioTrack.MODE_STREAM);
            at.play();

            byte[] buf = new byte[2048];
            int have = 0;
            while (running) {
                int n = in.read(buf, have, buf.length - have);
                if (n <= 0) break;
                int total = have + n;
                int even = total & ~1; // keep 16-bit samples aligned
                at.write(buf, 0, even);
                if (total > even) {
                    buf[0] = buf[total - 1];
                    have = 1;
                } else {
                    have = 0;
                }
            }
        } catch (IOException ignored) {
            // viewer released the button / timeout
        } finally {
            if (at != null) {
                try {
                    at.stop();
                } catch (Exception ignored) {
                }
                try {
                    at.release();
                } catch (Exception ignored) {
                }
            }
            try {
                if (am != null && oldVol >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, oldVol, 0);
            } catch (Exception ignored) {
            }
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Exception ignored) {
            }
            talking.set(false);
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String dec = new String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), "UTF-8");
            String expect = Prefs.get(svc).getString(Prefs.USER, "admin") + ":" + Prefs.password(svc);
            return MessageDigest.isEqual(dec.getBytes("UTF-8"), expect.getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }

    private void send(OutputStream out, int code, String msg, String type, byte[] body, String extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n");
        if (extra != null) sb.append(extra);
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes("ISO-8859-1");
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        out.write(all);
        out.flush();
    }

    private byte[] page() {
        if (pageBytes == null) {
            try {
                InputStream in = svc.getAssets().open("viewer.html");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                pageBytes = b.toByteArray();
            } catch (IOException e) {
                pageBytes = "viewer.html not found".getBytes();
            }
        }
        return pageBytes;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') {
                sb.append((char) c);
                if (sb.length() > 8192) throw new IOException("line too long");
            }
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i < 0) {
                    m.put(URLDecoder.decode(kv, "UTF-8"), "");
                } else {
                    m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                            URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
        return m;
    }
}
