package app.driftcast;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Driftcast wire protocol (same on Android and Windows).
 *
 * Discovery: receivers broadcast a JSON beacon on UDP {@link #BEACON_PORT} every second:
 *   {"app":"driftcast","v":1,"id":"...","name":"Living room TV","kind":"tv","port":47900}
 *
 * Stream: the phone opens TCP to the receiver's port and sends one line
 *   DRIFTCAST1 {"name":"Hakim's phone","id":"...","audio":true}\n
 * The receiver answers one line: {"ok":true} or {"ok":false,"reason":"denied"}.
 * After that both sides exchange packets: 1 byte type, 4 byte length (big endian),
 * 8 byte timestamp in microseconds (big endian), then length bytes of data.
 */
public final class Proto {
    private Proto() {}

    public static final int STREAM_PORT = 47900;
    public static final int BEACON_PORT = 47901;
    public static final String HELLO = "DRIFTCAST1 ";

    // phone -> receiver
    public static final int VIDEO_CONFIG = 1; // u16 width, u16 height, Annex B SPS+PPS
    public static final int VIDEO_DELTA = 2;  // Annex B access unit
    public static final int VIDEO_KEY = 3;    // Annex B access unit (key frame)
    public static final int AUDIO_PCM = 4;    // 48 kHz, stereo, s16le
    public static final int BYE = 5;
    public static final int PING = 6;
    // receiver -> phone
    public static final int REQUEST_KEY = 16;
    public static final int STOP = 17;

    public static final int HEADER = 13;
    public static final int MAX_PACKET = 8 * 1024 * 1024;

    public static byte[] header(int type, int len, long ptsUs) {
        byte[] h = new byte[HEADER];
        h[0] = (byte) type;
        h[1] = (byte) (len >>> 24);
        h[2] = (byte) (len >>> 16);
        h[3] = (byte) (len >>> 8);
        h[4] = (byte) len;
        for (int i = 0; i < 8; i++) h[5 + i] = (byte) (ptsUs >>> (56 - 8 * i));
        return h;
    }

    /** Writes one packet. Caller must synchronize on the stream. */
    public static void write(OutputStream out, int type, long ptsUs, byte[] data, int off, int len) throws IOException {
        out.write(header(type, len, ptsUs));
        if (len > 0) out.write(data, off, len);
    }

    public static final class Packet {
        public int type;
        public long pts;
        public byte[] data;
    }

    public static Packet read(DataInputStream in) throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException();
        int len = in.readInt();
        long pts = in.readLong();
        if (len < 0 || len > MAX_PACKET) throw new IOException("bad packet length " + len);
        Packet p = new Packet();
        p.type = type;
        p.pts = pts;
        p.data = new byte[len];
        in.readFully(p.data);
        return p;
    }

    /** Reads a \n terminated line (max 4 KB) without buffering past it. */
    public static String readLine(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\n') break;
            if (n == buf.length) throw new IOException("line too long");
            buf[n++] = (byte) b;
        }
        return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
    }

    public static void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
