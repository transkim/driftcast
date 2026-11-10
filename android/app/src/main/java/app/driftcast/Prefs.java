package app.driftcast;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small settings store. */
public final class Prefs {
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = this.ctx.getSharedPreferences("driftcast", Context.MODE_PRIVATE);
    }

    public String id() {
        String id = sp.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("id", id).apply();
        }
        return id;
    }

    public String name() {
        String n = sp.getString("name", null);
        if (n != null && !n.trim().isEmpty()) return n;
        String dev = null;
        if (Build.VERSION.SDK_INT >= 25) {
            try { dev = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME); }
            catch (Exception ignored) {}
        }
        if (dev == null || dev.trim().isEmpty()) {
            String model = Build.MODEL == null ? "" : Build.MODEL;
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
            dev = model.toLowerCase().startsWith(maker.toLowerCase()) ? model : (cap(maker) + " " + model).trim();
        }
        if (dev.isEmpty()) dev = isTv(ctx) ? "Android TV" : "Android phone";
        return dev;
    }

    public void setName(String n) { sp.edit().putString("name", n == null ? null : n.trim()).apply(); }

    /** Where the phone's sound plays while casting. */
    public static final int SOUND_OTHER = 0, SOUND_BOTH = 1, SOUND_PHONE = 2;

    public int soundRoute() {
        if (!sp.contains("route") && !sp.getBoolean("sound", true)) return SOUND_PHONE; // 0.1.0 setting
        return sp.getInt("route", SOUND_OTHER);
    }
    public void setSoundRoute(int r) { sp.edit().putInt("route", r).apply(); }

    /** True when the phone's sound should be captured and sent to the other screen. */
    public boolean sound() { return soundRoute() != SOUND_PHONE; }

    /** 0 = Smooth 720p, 1 = Sharp 1080p. */
    public int quality() { return sp.getInt("quality", 0); }
    public void setQuality(int q) { sp.edit().putInt("quality", q).apply(); }

    public boolean isTrusted(String id) { return sp.getStringSet("trusted", new HashSet<>()).contains(id); }
    public void trust(String id) {
        Set<String> s = new HashSet<>(sp.getStringSet("trusted", new HashSet<>()));
        s.add(id);
        sp.edit().putStringSet("trusted", s).apply();
    }
    public void forgetTrusted() { sp.edit().remove("trusted").apply(); }

    public static boolean isTv(Context c) {
        UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = c.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature("android.software.leanback_only");
    }

    public static String kind(Context c) {
        if (isTv(c)) return "tv";
        int sw = c.getResources().getConfiguration().smallestScreenWidthDp;
        return sw >= 600 ? "tablet" : "phone";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
