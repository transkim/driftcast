package app.driftcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Receiver: waits for a phone, then shows its screen and plays its sound. */
public class ReceiveActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Driftcast";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private SurfaceView video;
    private LinearLayout idle, bar;
    private TextView nameText, addrText, statusText, barText;
    private volatile android.view.Surface surface;
    private volatile CountDownLatch surfaceReady = new CountDownLatch(1);

    private Server server;
    private Discovery.Beacon beacon;
    private volatile Session session;
    private int videoW, videoH;
    private volatile boolean muted;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
    }

    // ---------------- UI ----------------
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        idle = Ui.column(this);
        idle.setBackgroundColor(Ui.BG);
        idle.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 32);
        idle.setPadding(pad, pad, pad, pad);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        idle.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        statusText = Ui.text(this, "Ready to receive", 18, Ui.GOOD, true);
        statusText.setGravity(Gravity.CENTER);
        idle.addView(statusText, Ui.lp(-2, -2, Ui.dp(this, 20)));
        nameText = Ui.text(this, "", 34, Ui.TEXT, true);
        nameText.setGravity(Gravity.CENTER);
        idle.addView(nameText, Ui.lp(-2, -2, Ui.dp(this, 6)));
        TextView how = Ui.text(this, "On your phone, open Driftcast, tap Cast my screen and pick this name.", 17, Ui.MUTED, false);
        how.setGravity(Gravity.CENTER);
        idle.addView(how, Ui.lp(-2, -2, Ui.dp(this, 14)));
        addrText = Ui.text(this, "", 14, Ui.MUTED, false);
        addrText.setGravity(Gravity.CENTER);
        idle.addView(addrText, Ui.lp(-2, -2, Ui.dp(this, 10)));

        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.CENTER);
        TextView rename = Ui.button(this, "Rename", false);
        rename.setOnClickListener(v -> rename());
        buttons.addView(rename);
        TextView forget = Ui.button(this, "Forget trusted phones", false);
        forget.setOnClickListener(v -> { prefs.forgetTrusted(); statusText.setText("Trusted phones forgotten"); });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
        fp.leftMargin = Ui.dp(this, 12);
        buttons.addView(forget, fp);
        if (Prefs.isTv(this)) {
            TextView cast = Ui.button(this, "Cast from this device", false);
            cast.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).putExtra("home", true)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = Ui.dp(this, 12);
            buttons.addView(cast, cp);
        }
        idle.addView(buttons, Ui.lp(-2, -2, Ui.dp(this, 28)));
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));

        bar = Ui.row(this);
        int bp = Ui.dp(this, 14);
        bar.setPadding(bp * 2, bp, bp, bp);
        bar.setBackground(Ui.round(0xE01A2350, Ui.dp(this, 24)));
        barText = Ui.text(this, "", 16, Ui.TEXT, true);
        bar.addView(barText, new LinearLayout.LayoutParams(-2, -2));
        TextView stop = Ui.button(this, "Stop", true);
        stop.setOnClickListener(v -> stopSession());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = Ui.dp(this, 20);
        bar.addView(stop, sp);
        TextView mute = Ui.button(this, "Mute", false);
        mute.setOnClickListener(v -> {
            muted = !muted;
            mute.setText(muted ? "Unmute" : "Mute");
            showBar();
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.leftMargin = Ui.dp(this, 10);
        bar.addView(mute, mp);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.bottomMargin = Ui.dp(this, 28);
        root.addView(bar, barLp);
        bar.setVisibility(View.GONE);

        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && session != null) showBar();
            return true;
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitVideo());
        setContentView(root);
        nameText.setText(prefs.name());
        rename.requestFocus();
    }

    private void rename() {
        EditText e = new EditText(this);
        e.setText(prefs.name());
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Name shown to phones")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setName(e.getText().toString());
                    nameText.setText(prefs.name());
                    restartBeacon();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddresses() {
        List<String> a = Discovery.localAddresses();
        String port = server != null && server.port != Proto.STREAM_PORT ? ":" + server.port : "";
        StringBuilder sb = new StringBuilder();
        for (String s : a) { if (sb.length() > 0) sb.append("   "); sb.append(s).append(port); }
        addrText.setText(a.isEmpty() ? "Not connected to Wi-Fi" : "Address: " + sb);
    }

    private final Runnable hideBar = () -> bar.setVisibility(View.GONE);

    private void showBar() {
        bar.setVisibility(View.VISIBLE);
        bar.getChildAt(1).requestFocus();
        ui.removeCallbacks(hideBar);
        ui.postDelayed(hideBar, 5000);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session != null && bar.getVisibility() != View.VISIBLE
                && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            showBar();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (session != null) { stopSession(); return; }
        super.onBackPressed();
    }

    private void setWatching(boolean on, String from) {
        if (on) {
            idle.setVisibility(View.GONE);
            barText.setText("Showing " + from);
            showBar();
            hideSystemUi();
        } else {
            idle.setVisibility(View.VISIBLE);
            bar.setVisibility(View.GONE);
            statusText.setText("Ready to receive");
            videoW = videoH = 0;
            fitVideo();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void fitVideo() {
        int rw = root.getWidth(), rh = root.getHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        int w = -1, h = -1;
        if (videoW > 0 && videoH > 0 && rw > 0 && rh > 0) {
            double s = Math.min(rw / (double) videoW, rh / (double) videoH);
            w = (int) Math.round(videoW * s);
            h = (int) Math.round(videoH * s);
        }
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
            video.setLayoutParams(lp);
        }
    }

    // ---------------- lifecycle ----------------
    @Override protected void onStart() {
        super.onStart();
        server = new Server();
        server.start();
    }

    @Override protected void onStop() {
        stopSession();
        if (beacon != null) { beacon.quit(); beacon = null; }
        if (server != null) { server.quit(); server = null; }
        super.onStop();
    }

    private void restartBeacon() {
        if (beacon != null) beacon.quit();
        if (server == null || server.port == 0) return;
        beacon = new Discovery.Beacon(prefs.id(), prefs.name(), Prefs.kind(this), server.port);
        beacon.start();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surface = h.getSurface(); surfaceReady.countDown(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { surface = h.getSurface(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surface = null;
        surfaceReady = new CountDownLatch(1);
        Session s = session;
        if (s != null) s.dropDecoder();
    }

    private void stopSession() {
        Session s = session;
        if (s != null) s.end(true);
    }

    // ---------------- network ----------------
    private final class Server extends Thread {
        private volatile ServerSocket ss;
        volatile int port;
        private volatile boolean alive = true;

        Server() { super("driftcast-server"); setDaemon(true); }

        void quit() {
            alive = false;
            try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        }

        @Override public void run() {
            int[] tries = {Proto.STREAM_PORT, Proto.STREAM_PORT + 10, Proto.STREAM_PORT + 20};
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
                ui.post(() -> statusText.setText("Could not open a network port"));
                return;
            }
            ui.post(() -> { restartBeacon(); showAddresses(); });
            while (alive) {
                try {
                    Socket c = ss.accept();
                    new Thread(() -> handle(c), "driftcast-conn").start();
                } catch (IOException e) {
                    if (!alive) break;
                }
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(10000);
            String line = Proto.readLine(c.getInputStream());
            if (!line.startsWith(Proto.HELLO)) { c.close(); return; }
            JSONObject hello = new JSONObject(line.substring(Proto.HELLO.length()));
            String name = hello.optString("name", "A phone");
            String id = hello.optString("id", "");
            OutputStream out = c.getOutputStream();
            synchronized (this) {
                if (session != null) {
                    Proto.writeLine(out, "{\"ok\":false,\"reason\":\"busy\"}");
                    c.close();
                    return;
                }
                session = new Session(c, name);
            }
            boolean ok = prefs.isTrusted(id) || ask(name, id);
            if (!ok || session == null || session.socket != c) {
                Proto.writeLine(out, "{\"ok\":false,\"reason\":\"denied\"}");
                c.close();
                synchronized (this) { if (session != null && session.socket == c) session = null; }
                return;
            }
            JSONObject okReply = new JSONObject().put("ok", true).put("name", prefs.name());
            org.json.JSONArray asked = hello.optJSONArray("sizes");
            if (asked != null) okReply.put("sizes", supportedSizes(asked));
            Proto.writeLine(out, okReply.toString());
            c.setSoTimeout(15000);
            session.run();
        } catch (Exception e) {
            Log.w(TAG, "connection ended", e);
            try { c.close(); } catch (IOException ignored) {}
            synchronized (this) { if (session != null && session.socket == c) session = null; }
        }
    }

    /** Which of the phone's proposed picture sizes this device's H.264 decoders can play. */
    private static org.json.JSONArray supportedSizes(org.json.JSONArray asked) {
        java.util.List<android.media.MediaCodecInfo.VideoCapabilities> caps = new java.util.ArrayList<>();
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        android.media.MediaCodecInfo.VideoCapabilities v =
                                info.getCapabilitiesForType(t).getVideoCapabilities();
                        if (v != null) caps.add(v);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "codec list", e);
        }
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < asked.length(); i++) {
            org.json.JSONArray wh = asked.optJSONArray(i);
            if (wh == null) continue;
            int w = wh.optInt(0), h = wh.optInt(1);
            boolean ok = caps.isEmpty(); // unknown: let the phone try
            for (android.media.MediaCodecInfo.VideoCapabilities v : caps) {
                try { if (v.isSizeSupported(w, h)) { ok = true; break; } } catch (Exception ignored) {}
            }
            if (ok) out.put(wh);
        }
        return out;
    }

    /** Shows Allow / Always allow / Deny and waits for the answer. */
    private boolean ask(String name, String id) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger answer = new AtomicInteger(0);
        AlertDialog[] dlg = new AlertDialog[1];
        ui.post(() -> {
            dlg[0] = new AlertDialog.Builder(this)
                    .setTitle(name + " wants to cast")
                    .setMessage("Show this phone's screen here?")
                    .setPositiveButton("Allow", (d, w) -> { answer.set(1); done.countDown(); })
                    .setNeutralButton("Always allow", (d, w) -> { answer.set(2); done.countDown(); })
                    .setNegativeButton("Deny", (d, w) -> done.countDown())
                    .setOnCancelListener(d -> done.countDown())
                    .show();
            dlg[0].getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        });
        boolean answered = done.await(60, TimeUnit.SECONDS);
        if (!answered) ui.post(() -> { if (dlg[0] != null) dlg[0].dismiss(); });
        if (answer.get() == 2) prefs.trust(id);
        return answer.get() > 0;
    }

    /** One phone's stream. */
    private final class Session {
        final Socket socket;
        final String from;
        private volatile boolean alive = true;
        private MediaCodec codec;
        private Thread drain;
        private volatile boolean needKey = true;
        private byte[] sps, pps;
        private int w, h;
        private AudioTrack track;
        private Thread audioThread;
        private final ArrayBlockingQueue<byte[]> audioQ = new ArrayBlockingQueue<>(12);
        private final Object codecLock = new Object();

        Session(Socket s, String from) { this.socket = s; this.from = from; }

        void run() throws IOException {
            ui.post(() -> setWatching(true, from));
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 256 * 1024));
            try {
                while (alive) {
                    Proto.Packet p = Proto.read(in);
                    switch (p.type) {
                        case Proto.VIDEO_CONFIG: onConfig(p.data); break;
                        case Proto.VIDEO_KEY: onFrame(p, true); break;
                        case Proto.VIDEO_DELTA: onFrame(p, false); break;
                        case Proto.AUDIO_PCM: onAudio(p.data); break;
                        case Proto.BYE: alive = false; break;
                        default: break;
                    }
                }
            } catch (IOException e) {
                if (alive) Log.w(TAG, "stream ended", e);
            } finally {
                end(false);
            }
        }

        void send(int type) {
            new Thread(() -> {
                try {
                    OutputStream o = socket.getOutputStream();
                    synchronized (o) { Proto.write(o, type, 0, null, 0, 0); o.flush(); }
                } catch (Exception ignored) {}
            }).start();
        }

        void end(boolean byUs) {
            synchronized (ReceiveActivity.this) {
                if (session != this) return;
                session = null;
            }
            alive = false;
            if (byUs) {
                try {
                    OutputStream o = socket.getOutputStream();
                    synchronized (o) { Proto.write(o, Proto.STOP, 0, null, 0, 0); o.flush(); }
                } catch (Exception ignored) {}
            }
            new Thread(() -> {
                try { Thread.sleep(byUs ? 300 : 0); } catch (InterruptedException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }).start();
            dropDecoder();
            if (audioThread != null) audioThread.interrupt();
            ui.post(() -> setWatching(false, null));
        }

        void dropDecoder() {
            synchronized (codecLock) {
                if (codec != null) {
                    try { codec.stop(); } catch (Exception ignored) {}
                    codec.release();
                    codec = null;
                }
                needKey = true;
            }
        }

        private void onConfig(byte[] d) {
            w = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            h = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
            sps = null; pps = null;
            // split Annex B NAL units
            int i = 4, start = -1;
            while (i <= d.length) {
                boolean sc = i + 3 <= d.length && d[i] == 0 && d[i + 1] == 0
                        && (d[i + 2] == 1 || (i + 4 <= d.length && d[i + 2] == 0 && d[i + 3] == 1));
                if (sc || i == d.length) {
                    if (start >= 0) storeNal(d, start, i);
                    if (i == d.length) break;
                    start = i;
                    i += d[i + 2] == 1 ? 3 : 4;
                } else {
                    i++;
                }
            }
            final int vw = w, vh = h;
            ui.post(() -> { videoW = vw; videoH = vh; fitVideo(); });
            startDecoder();
        }

        private void storeNal(byte[] d, int from, int to) {
            int hdr = from + (d[from + 2] == 1 ? 3 : 4);
            if (hdr >= to) return;
            int type = d[hdr] & 0x1F;
            byte[] nal = java.util.Arrays.copyOfRange(d, from, to);
            if (type == 7) sps = nal; else if (type == 8) pps = nal;
        }

        private void startDecoder() {
            dropDecoder();
            try {
                surfaceReady.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            android.view.Surface s = surface;
            if (s == null || !s.isValid() || sps == null) return;
            synchronized (codecLock) {
                try {
                    MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                    if (pps != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h * 3 / 2, 1 << 20));
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    MediaCodec mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                    try {
                        mc.configure(f, s, null, 0);
                    } catch (Exception e) {
                        f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                        if (pps != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h * 3 / 2, 1 << 20));
                        mc.reset();
                        mc.configure(f, s, null, 0);
                    }
                    mc.start();
                    codec = mc;
                    needKey = true;
                    final MediaCodec dc = mc;
                    drain = new Thread(() -> drainLoop(dc), "driftcast-drain");
                    drain.start();
                } catch (Exception e) {
                    Log.e(TAG, "decoder failed", e);
                    codec = null;
                    final int fw = w, fh = h;
                    ui.post(() -> android.widget.Toast.makeText(ReceiveActivity.this,
                            "This screen can't play a " + fw + "\u00D7" + fh + " picture. Try Smooth 720p on the phone.",
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }
            send(Proto.REQUEST_KEY);
        }

        private void drainLoop(MediaCodec mc) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (alive) {
                try {
                    int idx = mc.dequeueOutputBuffer(info, 20_000);
                    if (idx >= 0) mc.releaseOutputBuffer(idx, true);
                } catch (Exception e) {
                    break; // codec was released or reconfigured
                }
            }
        }

        private void onFrame(Proto.Packet p, boolean key) {
            if (codec == null) {
                // decoder was dropped (for example the screen was turned off); rebuild on a key frame
                if (!key || sps == null || surface == null) return;
                startDecoder();
            }
            synchronized (codecLock) {
                MediaCodec mc = codec;
                if (mc == null) return;
                if (needKey && !key) return;
                try {
                    int idx = mc.dequeueInputBuffer(30_000);
                    if (idx < 0) {
                        // decoder is behind: skip until the next key frame
                        needKey = true;
                        send(Proto.REQUEST_KEY);
                        return;
                    }
                    ByteBuffer buf = mc.getInputBuffer(idx);
                    buf.clear();
                    if (buf.capacity() < p.data.length) {
                        // frame too big for the decoder's buffer: skip to the next key frame
                        mc.queueInputBuffer(idx, 0, 0, p.pts, 0);
                        needKey = true;
                        send(Proto.REQUEST_KEY);
                        return;
                    }
                    buf.put(p.data);
                    mc.queueInputBuffer(idx, 0, p.data.length, p.pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    if (key) needKey = false;
                } catch (Exception e) {
                    Log.w(TAG, "decode error", e);
                    needKey = true;
                }
            }
        }

        private void onAudio(byte[] pcm) {
            if (track == null) startAudio();
            if (!audioQ.offer(pcm)) {
                audioQ.poll();
                audioQ.offer(pcm);
            }
        }

        private void startAudio() {
            int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            AudioFormat fmt = new AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            track = new AudioTrack(attrs, fmt, Math.max(min, 3840 * 4), AudioTrack.MODE_STREAM, 0);
            track.play();
            final AudioTrack t = track;
            audioThread = new Thread(() -> {
                try {
                    while (alive) {
                        byte[] b = audioQ.poll(500, TimeUnit.MILLISECONDS);
                        if (b == null) continue;
                        // keep latency low: if far behind, drop the backlog
                        if (audioQ.size() > 8) audioQ.clear();
                        if (!muted) t.write(b, 0, b.length);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    try { t.stop(); } catch (Exception ignored) {}
                    t.release();
                }
            }, "driftcast-sound");
            audioThread.start();
        }
    }
}
