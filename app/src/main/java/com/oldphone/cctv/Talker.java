package com.oldphone.cctv;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Viewer side of push-to-talk: records the microphone (16 kHz, mono, 16-bit PCM)
 * and sends the raw audio to the streamer's /talk endpoint while the button is held.
 */
public class Talker {

    public interface Callback {
        void onError(String message);
    }

    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean active = false;

    public Talker(Callback cb) {
        this.cb = cb;
    }

    public boolean isActive() {
        return active;
    }

    public void start(final String host, final int port, final String user, final String pass) {
        if (active) return;
        active = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioRecord rec = null;
                Socket sock = null;
                try {
                    sock = new Socket();
                    sock.connect(new InetSocketAddress(host, port), 3000);
                    sock.setTcpNoDelay(true);
                    OutputStream out = sock.getOutputStream();
                    String auth = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
                    out.write(("POST /talk HTTP/1.0\r\nAuthorization: Basic " + auth + "\r\n\r\n")
                            .getBytes("ISO-8859-1"));

                    int min = AudioRecord.getMinBufferSize(16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 6400));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("Microphone unavailable");
                    }
                    rec.startRecording();

                    byte[] b = new byte[640]; // 20 ms
                    while (active) {
                        int n = rec.read(b, 0, b.length);
                        if (n <= 0) break;
                        out.write(b, 0, n);
                    }
                    out.flush();
                } catch (final Exception e) {
                    if (active) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (cb != null) cb.onError("Talk failed: " + e.getMessage());
                            }
                        });
                    }
                } finally {
                    active = false;
                    if (rec != null) {
                        try {
                            rec.stop();
                        } catch (Exception ignored) {
                        }
                        try {
                            rec.release();
                        } catch (Exception ignored) {
                        }
                    }
                    if (sock != null) {
                        try {
                            sock.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "talker").start();
    }

    public void stop() {
        active = false;
    }
}
