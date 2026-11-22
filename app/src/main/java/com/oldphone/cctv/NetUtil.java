package com.oldphone.cctv;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;

public final class NetUtil {
    private NetUtil() {}

    /** IP Wi-Fi telefon ini (IPv4), atau null jika tiada. */
    public static String localIp(Context ctx) {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) return ip;
                        if (fallback == null && a.isSiteLocalAddress()) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (fallback != null) return fallback;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Semak sama ada host:port ialah streamer Old Phone CCTV (guna /ping tanpa login). */
    public static boolean isCctv(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 350);
            s.setSoTimeout(700);
            OutputStream o = s.getOutputStream();
            o.write("GET /ping HTTP/1.0\r\n\r\n".getBytes("US-ASCII"));
            o.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return false;
            String t = new String(buf, 0, n, "US-ASCII");
            return t.contains("OLDPHONE-CCTV");
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
