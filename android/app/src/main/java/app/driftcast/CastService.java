package app.driftcast;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service that captures the screen (and sound) and streams it to one receiver.
 */
public class CastService extends Service {
    private static final String TAG = "Driftcast";
    public static final String ACTION_START = "app.driftcast.START";
    public static final String ACTION_STOP = "app.driftcast.STOP";
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data", EXTRA_HOST = "host",
            EXTRA_PORT = "port", EXTRA_TARGET = "target";
    private static final String CHANNEL = "cast";
    private static final int NOTE_ID = 7;

    // ---- status shared with the UI ----
    public enum Status { IDLE, CONNECTING, WAITING, CASTING }
    public interface StatusListener { void onStatus(Status s, String target, String message); }
    private static final CopyOnWriteArrayList<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private static volatile Status status = Status.IDLE;
    private static volatile String statusTarget = "", statusMessage = "";
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static Status status() { return status; }
    public static String target() { return statusTarget; }
    public static void addListener(StatusListener l) {
        listeners.add(l);
        l.onStatus(status, statusTarget, status == Status.IDLE ? "" : statusMessage);
    }
    public static void removeListener(StatusListener l) { listeners.remove(l); }

    private static void setStatus(Status s, String target, String msg) {
        status = s; statusTarget = target == null ? "" : target; statusMessage = msg == null ? "" : msg;
        main.post(() -> { for (StatusListener l : listeners) l.onStatus(status, statusTarget, statusMessage); });
    }

    // ---- session ----
    private MediaProjection projection;
    private volatile Socket socket;
    private volatile boolean running;
    private Thread sessionThread;
    private Encoder encoder;
    private AudioCapture audio;
    private Writer writer;
    private String targetName = "";
    /** Sizes the receiver's decoder accepts ("WxH"), or null when it takes anything. */
    private volatile java.util.Set<String> supported;
    private static final int[] LONG_SIDES = {1920, 1600, 1440, 1280, 1088, 960, 864, 720, 640, 480};

    private int[] realSize() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels, m.densityDpi};
    }

    /** w x h scaled so the long side is at most L, rounded to multiples of 16. */
    static int[] scaled(int w, int h, int L) {
        double scale = Math.min(1.0, L / (double) Math.max(w, h));
        return new int[]{Math.max(16, (int) Math.round(w * scale / 16.0) * 16),
                Math.max(16, (int) Math.round(h * scale / 16.0) * 16)};
    }

    // ---- where the sound plays (can change while casting) ----
    private static volatile CastService active;
    private volatile boolean sendSound;
    private int savedVolume = -1;

    /** Applies the Sound plays on choice to a running cast. Returns false if the cast has no sound capture. */
    public static boolean applySoundRoute(int route) {
        CastService s = active;
        if (s == null || !s.running) return true;
        main.post(() -> s.routeSound(route));
        return route == Prefs.SOUND_PHONE || s.audio != null;
    }

    private void routeSound(int route) {
        sendSound = route != Prefs.SOUND_PHONE && audio != null;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (route == Prefs.SOUND_OTHER && audio != null) {
                // silence the phone's speaker; the captured copy still goes to the other screen
                if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } else {
                restoreVolume();
            }
        } catch (Exception e) {
            Log.w(TAG, "could not change phone volume", e);
        }
    }

    private void restoreVolume() {
        if (savedVolume < 0) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            // only put it back if the person hasn't turned it up themselves meanwhile
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
        } catch (Exception ignored) {}
        savedVolume = -1;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown("Stopped");
            return START_NOT_STICKY;
        }
        if (running) shutdown(null);

        targetName = intent.getStringExtra(EXTRA_TARGET);
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, Proto.STREAM_PORT);
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        Prefs prefs = new Prefs(this);
        boolean wantSound = prefs.sound() && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        Notification n = buildNotification("Connecting to " + targetName + "…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (wantSound && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTE_ID, n, type);
            } else {
                startForeground(NOTE_ID, n);
            }
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            Log.e(TAG, "could not start capture", e);
            setStatus(Status.IDLE, targetName, "Android did not allow screen capture");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null) {
            setStatus(Status.IDLE, targetName, "Android did not allow screen capture");
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { if (running) shutdown("Screen sharing ended"); }
        }, main);

        running = true;
        setStatus(Status.CONNECTING, targetName, "");
        final boolean sound = wantSound;
        sessionThread = new Thread(() -> runSession(host, port, sound, prefs), "driftcast-session");
        sessionThread.start();
        return START_NOT_STICKY;
    }

    private void runSession(String host, int port, boolean sound, Prefs prefs) {
        String endMsg = "Cast ended";
        try {
            Socket s = new Socket();
            socket = s;
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setTcpNoDelay(true);
            s.setSendBufferSize(512 * 1024);
            OutputStream rawOut = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            JSONObject hello = new JSONObject();
            hello.put("name", prefs.name()).put("id", prefs.id()).put("audio", sound)
                    .put("model", Build.MODEL).put("app", BuildConfig.VERSION_NAME);
            // every picture size we might send; the receiver says which its decoder can play
            org.json.JSONArray cands = new org.json.JSONArray();
            int[] real = realSize();
            for (int L : LONG_SIDES) {
                int[] a = scaled(real[0], real[1], L), b = scaled(real[1], real[0], L);
                cands.put(new org.json.JSONArray().put(a[0]).put(a[1]));
                cands.put(new org.json.JSONArray().put(b[0]).put(b[1]));
            }
            hello.put("sizes", cands);
            Proto.writeLine(rawOut, Proto.HELLO + hello);
            setStatus(Status.WAITING, targetName, "");
            updateNotification("Waiting for " + targetName + " to allow…");
            s.setSoTimeout(65000);
            JSONObject reply = new JSONObject(Proto.readLine(in));
            if (!reply.optBoolean("ok")) {
                String r = reply.optString("reason");
                throw new IOException("busy".equals(r) ? targetName + " is showing another phone"
                        : targetName + " said no");
            }
            s.setSoTimeout(0);
            if (!running) return;
            supported = null;
            org.json.JSONArray ok = reply.optJSONArray("sizes");
            if (ok != null) {
                supported = new java.util.HashSet<>();
                for (int i = 0; i < ok.length(); i++) {
                    org.json.JSONArray wh = ok.optJSONArray(i);
                    if (wh != null) supported.add(wh.optInt(0) + "x" + wh.optInt(1));
                }
            }

            writer = new Writer(new BufferedOutputStream(rawOut, 128 * 1024));
            writer.start();
            encoder = new Encoder(prefs.quality());
            encoder.start();
            if (sound) {
                audio = new AudioCapture();
                audio.start();
            }
            active = this;
            final int route = prefs.soundRoute();
            main.post(() -> routeSound(route));
            setStatus(Status.CASTING, targetName, "");
            updateNotification("Casting to " + targetName);

            while (running) {
                Proto.Packet p = Proto.read(in);
                if (p.type == Proto.REQUEST_KEY) { if (encoder != null) encoder.requestKey(); }
                else if (p.type == Proto.STOP) { endMsg = targetName + " stopped the cast"; break; }
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "session ended", e);
                String m = e.getMessage();
                if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException)
                    m = "Could not reach " + targetName + ". Same Wi-Fi?";
                else if (!(e instanceof IOException) || m == null || m.contains("ECONN") || m.contains("closed") || m.contains("EOF"))
                    m = "Connection to " + targetName + " was lost";
                endMsg = m;
            }
        }
        final String msg = endMsg;
        main.post(() -> shutdown(msg));
    }

    private synchronized void shutdown(String message) {
        boolean was = running;
        running = false;
        if (writer != null) { writer.quit(); writer = null; }
        if (audio != null) { audio.quit(); audio = null; }
        sendSound = false;
        restoreVolume();
        if (active == this) active = null;
        if (encoder != null) { encoder.quit(); encoder = null; }
        Socket s = socket;
        socket = null;
        if (s != null) {
            new Thread(() -> {
                try {
                    OutputStream o = s.getOutputStream();
                    synchronized (o) { Proto.write(o, Proto.BYE, 0, null, 0, 0); o.flush(); }
                } catch (Exception ignored) {}
                try { s.close(); } catch (Exception ignored) {}
            }).start();
        }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (was || (message != null && status != Status.IDLE)) setStatus(Status.IDLE, targetName, message);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running) shutdown("Stopped");
        super.onDestroy();
    }

    // ---- notification ----
    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while your screen is being cast");
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CastActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle("Driftcast")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_cast, "Stop", stop).build());
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try { nm.notify(NOTE_ID, buildNotification(text)); } catch (Exception ignored) {}
    }

    // ---- network writer: one thread, drops video when the Wi-Fi can't keep up ----
    private final class Writer extends Thread {
        private final OutputStream out;
        private final LinkedBlockingDeque<Object[]> queue = new LinkedBlockingDeque<>();
        private final AtomicLong queuedBytes = new AtomicLong();
        private volatile boolean alive = true;
        private volatile boolean dropping;
        private int queuedAudio;

        Writer(OutputStream out) { super("driftcast-writer"); this.out = out; }

        void quit() { alive = false; interrupt(); }

        void video(int type, long pts, byte[] data) {
            if (type == Proto.VIDEO_DELTA) {
                if (dropping) return;
                if (queuedBytes.get() > 1_500_000) {
                    dropping = true;
                    if (encoder != null) encoder.requestKey();
                    return;
                }
            } else {
                dropping = false;
            }
            queuedBytes.addAndGet(data.length);
            queue.offer(new Object[]{type, pts, data});
        }

        synchronized void audio(long pts, byte[] data) {
            if (queuedAudio > 15) return; // over 300 ms behind: skip sound rather than lag
            queuedAudio++;
            queue.offer(new Object[]{Proto.AUDIO_PCM, pts, data});
        }

        @Override public void run() {
            try {
                while (alive) {
                    Object[] item = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        synchronized (out) { Proto.write(out, Proto.PING, 0, null, 0, 0); out.flush(); }
                        continue;
                    }
                    int type = (Integer) item[0];
                    byte[] d = (byte[]) item[2];
                    synchronized (out) {
                        Proto.write(out, type, (Long) item[1], d, 0, d.length);
                        if (queue.isEmpty()) out.flush();
                    }
                    if (type == Proto.AUDIO_PCM) synchronized (this) { queuedAudio--; }
                    else queuedBytes.addAndGet(-d.length);
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                if (alive) main.post(() -> shutdown("Connection to " + targetName + " was lost"));
            }
        }
    }

    // ---- screen encoder ----
    private final class Encoder extends Thread implements DisplayManager.DisplayListener {
        private final int quality;
        private volatile boolean alive = true, restart, wantKey;
        private VirtualDisplay display;
        private HandlerThread listenerThread;

        Encoder(int quality) { super("driftcast-encoder"); this.quality = quality; }

        void requestKey() { wantKey = true; }
        void quit() { alive = false; }

        private int[] size() {
            int[] r = realSize();
            int max = quality == 1 ? 1920 : 1280;
            java.util.Set<String> ok = supported;
            int[] last = null;
            for (int L : LONG_SIDES) {
                if (L > max) continue;
                int[] c = scaled(r[0], r[1], L);
                last = c;
                if (ok == null || ok.contains(c[0] + "x" + c[1])) return new int[]{c[0], c[1], r[2]};
            }
            // nothing listed as supported: send the smallest picture and hope for the best
            Log.w(TAG, "receiver supports none of our sizes");
            return new int[]{last[0], last[1], r[2]};
        }

        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) restart = true;
        }

        @Override public void run() {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            listenerThread = new HandlerThread("driftcast-display");
            listenerThread.start();
            dm.registerDisplayListener(this, new Handler(listenerThread.getLooper()));
            try {
                while (alive && running) {
                    restart = false;
                    int[] sz = size();
                    runEncoder(sz[0], sz[1], sz[2]);
                }
            } catch (Exception e) {
                Log.e(TAG, "encoder failed", e);
                if (alive) main.post(() -> shutdown("This phone's video encoder failed"));
            } finally {
                dm.unregisterDisplayListener(this);
                listenerThread.quitSafely();
                if (display != null) { display.release(); display = null; }
            }
        }

        private void runEncoder(int w, int h, int dpi) throws IOException {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, quality == 1 ? 8_000_000 : 5_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
            if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            Surface surface = null;
            try {
                try {
                    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                } catch (Exception e) {
                    // some encoders reject the low-latency extras; fall back to plain settings
                    f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                    f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    f.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
                    f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                    f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                    codec.reset();
                    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                }
                surface = codec.createInputSurface();
                if (display == null) {
                    display = projection.createVirtualDisplay("Driftcast", w, h, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
                } else {
                    display.resize(w, h, dpi);
                    display.setSurface(surface);
                }
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (alive && running) {
                    if (restart) {
                        // rotation changes the size: rebuild the encoder; other display changes are ignored
                        restart = false;
                        int[] now = size();
                        if (now[0] != w || now[1] != h) { restart = true; break; }
                    }
                    if (wantKey) {
                        wantKey = false;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(b);
                    }
                    int idx = codec.dequeueOutputBuffer(info, 50_000);
                    if (idx < 0) continue;
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0 && writer != null) {
                        byte[] data;
                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        int off = config ? 4 : 0;
                        data = new byte[info.size + off];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(data, off, info.size);
                        if (config) {
                            data[0] = (byte) (w >> 8); data[1] = (byte) w;
                            data[2] = (byte) (h >> 8); data[3] = (byte) h;
                            writer.video(Proto.VIDEO_CONFIG, 0, data);
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            writer.video(key ? Proto.VIDEO_KEY : Proto.VIDEO_DELTA, info.presentationTimeUs, data);
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                }
            } finally {
                if (display != null) display.setSurface(null);
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
                if (surface != null) surface.release();
            }
        }
    }

    // ---- sound (Android 10+) ----
    private final class AudioCapture extends Thread {
        private volatile boolean alive = true;
        AudioCapture() { super("driftcast-audio"); }
        void quit() { alive = false; }

        @Override public void run() {
            if (Build.VERSION.SDK_INT < 29) return;
            AudioRecord rec = null;
            try {
                AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build();
                int chunk = 3840; // 20 ms
                int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(Math.max(min, chunk * 4))
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build();
                rec.startRecording();
                while (alive && running) {
                    byte[] b = new byte[chunk];
                    int n = 0;
                    while (n < chunk && alive) {
                        int r = rec.read(b, n, chunk - n);
                        if (r <= 0) break;
                        n += r;
                    }
                    if (n == chunk && writer != null && sendSound) writer.audio(System.nanoTime() / 1000, b);
                    else if (n <= 0) Thread.sleep(10);
                }
            } catch (Exception e) {
                Log.w(TAG, "sound capture unavailable", e);
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
            }
        }
    }
}
