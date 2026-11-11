package app.driftcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Keeps this screen ready to receive even when the Driftcast window is closed or the TV has been
 * idle: it holds the network port, keeps announcing the name, keeps Wi-Fi awake, and brings the
 * receive screen up when a phone connects.
 */
public class ReceiverService extends Service {
    private static final String TAG = "Driftcast";
    private static final String CHANNEL = "ready", CHANNEL_CALL = "incoming";
    private static final int NOTE_ID = 11, NOTE_INCOMING = 12;
    public static final String ACTION_STOP = "app.driftcast.RECEIVER_STOP";

    /** The open receive screen. */
    public interface Sink { void onIncoming(Socket socket, JSONObject hello); }

    private static volatile ReceiverService instance;
    private static volatile Sink sink;
    private static volatile int port;
    private static Socket pendingSocket;
    private static JSONObject pendingHello;
    private static long pendingAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket ss;
    private volatile boolean alive;
    private Discovery.Beacon beacon;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock cpuLock;

    // ---------------- static API for the activity ----------------
    public static void start(Context c) {
        Intent i = new Intent(c, ReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start receiver service", e);
        }
    }

    public static void stop(Context c) { c.stopService(new Intent(c, ReceiverService.class)); }

    public static int port() { return port; }

    /** Re-announce with the current name. */
    public static void refresh() {
        ReceiverService s = instance;
        if (s != null) s.main.post(s::restartBeacon);
    }

    /** Called by the receive screen when it is visible. Hands over a phone that was waiting. */
    public static void attach(Sink s) {
        Socket c;
        JSONObject h;
        synchronized (ReceiverService.class) {
            sink = s;
            c = pendingSocket;
            h = pendingHello;
            boolean fresh = SystemClock.elapsedRealtime() - pendingAt < 55_000;
            pendingSocket = null;
            pendingHello = null;
            if (c != null && !fresh) { try { c.close(); } catch (IOException ignored) {} c = null; }
        }
        ReceiverService svc = instance;
        if (svc != null) ((NotificationManager) svc.getSystemService(NOTIFICATION_SERVICE)).cancel(NOTE_INCOMING);
        if (c != null) {
            final Socket fc = c;
            final JSONObject fh = h;
            new Thread(() -> s.onIncoming(fc, fh), "driftcast-conn").start();
        }
    }

    public static void detach(Sink s) {
        synchronized (ReceiverService.class) { if (sink == s) sink = null; }
    }

    // ---------------- service ----------------
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = readyNotification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTE_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "receiver could not go foreground", e);
        }
        if (!alive) {
            alive = true;
            acquireLocks();
            new Thread(this::serve, "driftcast-server").start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        alive = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        if (beacon != null) { beacon.quit(); beacon = null; }
        releaseLocks();
        port = 0;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "driftcast:ready");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "driftcast:ready");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "locks", e);
        }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) {}
    }

    private void restartBeacon() {
        if (beacon != null) beacon.quit();
        beacon = null;
        if (!alive || port == 0) return;
        Prefs prefs = new Prefs(this);
        beacon = new Discovery.Beacon(prefs.id(), prefs.name(), Prefs.kind(this), port);
        beacon.start();
    }

    private void serve() {
        int[] tries = {Proto.STREAM_PORT, Proto.STREAM_PORT + 10, Proto.STREAM_PORT + 20};
        while (alive) {
            for (int p : tries) {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(p));
                    ss = s;
                    port = p;
                    break;
                } catch (IOException e) {
                    Log.w(TAG, "port " + p + " busy");
                }
            }
            if (ss == null) {
                // the network may not be up yet (just booted): try again shortly
                SystemClock.sleep(3000);
                continue;
            }
            main.post(this::restartBeacon);
            while (alive) {
                try {
                    Socket c = ss.accept();
                    new Thread(() -> greet(c), "driftcast-hello").start();
                } catch (IOException e) {
                    break;
                }
            }
            try { ss.close(); } catch (IOException ignored) {}
            ss = null;
            if (alive) SystemClock.sleep(1000);
        }
    }

    /** Reads the phone's hello, then passes it to the screen (opening it if needed). */
    private void greet(Socket c) {
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(10000);
            String line = Proto.readLine(c.getInputStream());
            if (!line.startsWith(Proto.HELLO)) { c.close(); return; }
            JSONObject hello = new JSONObject(line.substring(Proto.HELLO.length()));
            Sink s;
            synchronized (ReceiverService.class) {
                s = sink;
                if (s == null) {
                    if (pendingSocket != null) { try { pendingSocket.close(); } catch (IOException ignored) {} }
                    pendingSocket = c;
                    pendingHello = hello;
                    pendingAt = SystemClock.elapsedRealtime();
                }
            }
            if (s != null) s.onIncoming(c, hello);
            else main.post(() -> bringUp(hello.optString("name", "A phone")));
        } catch (Exception e) {
            Log.w(TAG, "hello failed", e);
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void bringUp(String from) {
        // wake the screen
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "driftcast:incoming");
            wl.acquire(10_000);
        } catch (Exception ignored) {}
        Intent open = new Intent(this, ReceiveActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // a direct start works on most TVs; the full-screen notification covers phones and stricter TVs
        try { startActivity(open); } catch (Exception e) { Log.w(TAG, "direct open blocked", e); }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_CALL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_CALL, "Phone wants to cast", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_CALL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle(from + " wants to cast")
                .setContentText("Tap to show it on this screen")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60_000);
        try { nm.notify(NOTE_INCOMING, b.build()); } catch (Exception ignored) {}
    }

    private Notification readyNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ready to receive", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Shown while this device waits for a phone to cast");
            nm.createNotificationChannel(ch);
        }
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ReceiveActivity.class), f);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ReceiverService.class).setAction(ACTION_STOP), f);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle("Driftcast is ready to receive")
                .setContentText("Phones on this Wi-Fi can cast to " + new Prefs(this).name())
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_cast, "Stop receiving", stop).build())
                .build();
    }
}
