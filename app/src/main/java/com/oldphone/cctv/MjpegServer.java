package com.oldphone.cctv;

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
            svc.serverError = "Port " + port + " tidak boleh dibuka: " + e.getMessage();
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
                send(out, 401, "Unauthorized", "text/plain", "Perlu login".getBytes("UTF-8"),
                        "WWW-Authenticate: Basic realm=\"Old Phone CCTV\"\r\n");
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "OK", "text/html; charset=utf-8", page(), null);
            } else if (path.equals("/stream")) {
                stream(out);
            } else if (path.equals("/snapshot") || path.equals("/snapshot.jpg")) {
                StreamService.Frame f = svc.getFrame(0, 3000);
                if (f == null || svc.frameAgeMs() > 8000) {
                    send(out, 503, "Service Unavailable", "text/plain", "Kamera belum sedia".getBytes("UTF-8"), null);
                } else {
                    send(out, 200, "OK", "image/jpeg", f.data, null);
                }
            } else if (path.equals("/api/status")) {
                send(out, 200, "OK", "application/json", svc.statusJson().getBytes("UTF-8"), null);
            } else if (path.equals("/api/set")) {
                if (!"POST".equals(method)) {
                    send(out, 405, "Method Not Allowed", "text/plain", "Guna POST".getBytes("UTF-8"), null);
                } else {
                    svc.updateSettings(parseQuery(query));
                    send(out, 200, "OK", "application/json", svc.statusJson().getBytes("UTF-8"), null);
                }
            } else {
                send(out, 404, "Not Found", "text/plain", "Tiada".getBytes("UTF-8"), null);
            }
        } catch (Exception ignored) {
            // klien putus sambungan, tamat masa, dsb.
        }
    }

    private void stream(OutputStream out) throws IOException, InterruptedException {
        streams.incrementAndGet();
        try {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("ISO-8859-1"));
            out.flush();
            long seq = 0;
            while (running && svc.isRunningFlag()) {
                StreamService.Frame f = svc.getFrame(seq, 5000);
                if (f == null) continue;
                seq = f.seq;
                String part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.data.length + "\r\n\r\n";
                out.write(part.getBytes("ISO-8859-1"));
                out.write(f.data);
                out.write("\r\n".getBytes("ISO-8859-1"));
                out.flush();
            }
        } finally {
            streams.decrementAndGet();
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
                pageBytes = "viewer.html tidak dijumpai".getBytes();
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
                if (sb.length() > 8192) throw new IOException("baris terlalu panjang");
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
