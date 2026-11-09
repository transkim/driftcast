package app.driftcast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UDP broadcast discovery: receivers announce, senders listen. */
public final class Discovery {
    private static final String TAG = "Driftcast";

    private Discovery() {}

    /** A screen that can receive a cast. */
    public static final class Screen {
        public String id, name, kind, host;
        public int port;
        public long seen;
    }

    /** IPv4 addresses of this device on Wi-Fi / Ethernet, for display. */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static Set<InetAddress> broadcastTargets() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Runs on the receiver: shouts its name once a second. */
    public static final class Beacon extends Thread {
        private final JSONObject info;
        private volatile boolean running = true;

        public Beacon(String id, String name, String kind, int port) {
            super("driftcast-beacon");
            setDaemon(true);
            info = new JSONObject();
            try {
                info.put("app", "driftcast").put("v", 1).put("id", id).put("name", name)
                        .put("kind", kind).put("port", port);
            } catch (Exception ignored) {}
        }

        public void quit() { running = false; interrupt(); }

        @Override public void run() {
            byte[] msg = info.toString().getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                while (running) {
                    for (InetAddress to : broadcastTargets()) {
                        try { s.send(new DatagramPacket(msg, msg.length, to, Proto.BEACON_PORT)); }
                        catch (Exception ignored) {}
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            } catch (Exception e) {
                Log.w(TAG, "beacon stopped", e);
            }
        }
    }

    public interface Listener { void onScreens(List<Screen> screens); }

    /** Runs on the phone: listens for beacons and keeps a fresh list. */
    public static final class Scanner extends Thread {
        private final Listener listener;
        private final WifiManager.MulticastLock lock;
        private final Map<String, Screen> screens = new LinkedHashMap<>();
        private final String selfId;
        private volatile boolean running = true;
        private volatile DatagramSocket socket;

        public Scanner(Context ctx, String selfId, Listener listener) {
            super("driftcast-scan");
            setDaemon(true);
            this.listener = listener;
            this.selfId = selfId;
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wm != null ? wm.createMulticastLock("driftcast") : null;
        }

        public void quit() {
            running = false;
            DatagramSocket s = socket;
            if (s != null) s.close();
        }

        @Override public void run() {
            if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(Proto.BEACON_PORT));
                s.setSoTimeout(1000);
                socket = s;
                byte[] buf = new byte[2048];
                long lastPush = 0;
                while (running) {
                    boolean changed = false;
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"driftcast".equals(o.optString("app"))) continue;
                        String id = o.optString("id");
                        if (id.isEmpty() || id.equals(selfId)) continue;
                        Screen sc = screens.get(id);
                        if (sc == null) { sc = new Screen(); screens.put(id, sc); changed = true; }
                        String host = p.getAddress().getHostAddress();
                        String name = o.optString("name", "Screen");
                        if (!host.equals(sc.host) || !name.equals(sc.name)) changed = true;
                        sc.id = id;
                        sc.name = name;
                        sc.kind = o.optString("kind", "screen");
                        sc.port = o.optInt("port", Proto.STREAM_PORT);
                        sc.host = host;
                        sc.seen = SystemClock.elapsedRealtime();
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (!running) break;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (screens.values().removeIf(x -> now - x.seen > 4500)) changed = true;
                    if (changed || now - lastPush > 2000) {
                        lastPush = now;
                        listener.onScreens(new ArrayList<>(screens.values()));
                    }
                }
                s.close();
            } catch (Exception e) {
                Log.w(TAG, "scanner stopped", e);
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
        }
    }
}
