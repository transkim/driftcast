package app.driftcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Sender: pick a screen, then cast. */
public class CastActivity extends Activity implements CastService.StatusListener {
    private static final int REQ_CAPTURE = 1, REQ_PERMS = 2;

    private Prefs prefs;
    private Discovery.Scanner scanner;
    private LinearLayout list, statusCard;
    private TextView statusTitle, statusSub, hint;
    private TextView smooth, sharp;
    private TextView soundOther, soundBoth, soundPhone, routeHint;
    private Discovery.Screen pending;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        int pad = Ui.dp(this, 20);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = Ui.column(this);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        col.addView(Ui.text(this, "Cast my screen", 28, Ui.TEXT, true));

        // status card (visible while casting)
        statusCard = Ui.column(this);
        statusCard.setPadding(pad, pad, pad, pad);
        statusCard.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 20)));
        statusTitle = Ui.text(this, "", 20, 0xFFFFFFFF, true);
        statusSub = Ui.text(this, "", 14, 0xF0FFFFFF, false);
        statusCard.addView(statusTitle);
        statusCard.addView(statusSub, Ui.lp(-2, -2, Ui.dp(this, 4)));
        TextView stop = Ui.button(this, "Stop casting", false);
        stop.setOnClickListener(v -> startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP)));
        statusCard.addView(stop, Ui.lp(-1, -2, Ui.dp(this, 16)));
        col.addView(statusCard, Ui.lp(-1, -2, Ui.dp(this, 20)));

        // options
        LinearLayout opts = Ui.row(this);
        smooth = chip("Smooth 720p");
        sharp = chip("Sharp 1080p");
        smooth.setOnClickListener(v -> { prefs.setQuality(0); paintQuality(); });
        sharp.setOnClickListener(v -> { prefs.setQuality(1); paintQuality(); });
        opts.addView(smooth);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = Ui.dp(this, 8);
        opts.addView(sharp, sp);
        col.addView(opts, Ui.lp(-1, -2, Ui.dp(this, 20)));
        paintQuality();

        col.addView(Ui.text(this, "Sound plays on", 14, Ui.MUTED, true), Ui.lp(-2, -2, Ui.dp(this, 20)));
        if (Build.VERSION.SDK_INT >= 29) {
            LinearLayout routes = Ui.row(this);
            soundOther = chip("TV / laptop");
            soundBoth = chip("Both");
            soundPhone = chip("This phone");
            soundOther.setOnClickListener(v -> setRoute(Prefs.SOUND_OTHER));
            soundBoth.setOnClickListener(v -> setRoute(Prefs.SOUND_BOTH));
            soundPhone.setOnClickListener(v -> setRoute(Prefs.SOUND_PHONE));
            routes.addView(soundOther);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.leftMargin = Ui.dp(this, 8);
            routes.addView(soundBoth, rp);
            LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(-2, -2);
            rp2.leftMargin = Ui.dp(this, 8);
            routes.addView(soundPhone, rp2);
            col.addView(routes, Ui.lp(-1, -2, Ui.dp(this, 8)));
            routeHint = Ui.text(this, "", 13, Ui.MUTED, false);
            col.addView(routeHint, Ui.lp(-1, -2, Ui.dp(this, 6)));
            paintRoute();
        } else {
            col.addView(Ui.text(this, "This phone only. Sending sound needs Android 10 or later.", 15, Ui.TEXT, false),
                    Ui.lp(-1, -2, Ui.dp(this, 6)));
        }

        col.addView(Ui.text(this, "Screens on this Wi-Fi", 14, Ui.MUTED, true), Ui.lp(-2, -2, Ui.dp(this, 28)));
        list = Ui.column(this);
        col.addView(list, Ui.lp(-1, -2, Ui.dp(this, 8)));
        hint = Ui.text(this, "Looking for screens…\n\nOn your TV, laptop or other phone, open Driftcast and choose Receive. "
                + "Both devices must be on the same Wi-Fi.", 15, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(hint);

        TextView manual = Ui.button(this, "Enter an address instead", false);
        manual.setOnClickListener(v -> manualAddress());
        col.addView(manual, Ui.lp(-1, -2, Ui.dp(this, 20)));

        setContentView(scroll);
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 15, Ui.TEXT, true);
        int ph = Ui.dp(this, 16), pv = Ui.dp(this, 10);
        t.setPadding(ph, pv, ph, pv);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void paintQuality() {
        boolean hi = prefs.quality() == 1;
        Ui.cardStyle(smooth, hi ? Ui.CARD : Ui.CARD_HI);
        Ui.cardStyle(sharp, hi ? Ui.CARD_HI : Ui.CARD);
        smooth.setTextColor(hi ? Ui.MUTED : Ui.ACCENT);
        sharp.setTextColor(hi ? Ui.ACCENT : Ui.MUTED);
    }

    private void setRoute(int r) {
        prefs.setSoundRoute(r);
        paintRoute();
        if (!CastService.applySoundRoute(r))
            Toast.makeText(this, "Stop and cast again to send sound to the other screen", Toast.LENGTH_LONG).show();
    }

    private void paintRoute() {
        int r = prefs.soundRoute();
        TextView[] all = {soundOther, soundBoth, soundPhone};
        for (int i = 0; i < 3; i++) {
            Ui.cardStyle(all[i], i == r ? Ui.CARD_HI : Ui.CARD);
            all[i].setTextColor(i == r ? Ui.ACCENT : Ui.MUTED);
        }
        routeHint.setText(r == Prefs.SOUND_OTHER
                ? "The phone goes quiet and the sound comes out of the TV or laptop. If you hear nothing there, pick Both."
                : r == Prefs.SOUND_BOTH ? "Sound comes out of the phone and the other screen together."
                : "Only the picture is sent. Sound stays on this phone, so you can use earphones.");
    }

    @Override protected void onStart() {
        super.onStart();
        CastService.addListener(this);
        shown = null;
        scanner = new Discovery.Scanner(this, prefs.id(), screens -> runOnUiThread(() -> showScreens(screens)));
        scanner.start();
    }

    @Override protected void onStop() {
        CastService.removeListener(this);
        if (scanner != null) { scanner.quit(); scanner = null; }
        super.onStop();
    }

    private String shown = null;

    private void showScreens(List<Discovery.Screen> screens) {
        // rebuild only when something changed, otherwise a tap that lands mid-rebuild is lost
        StringBuilder key = new StringBuilder();
        for (Discovery.Screen s : screens) key.append(s.id).append('|').append(s.name).append('|').append(s.host).append(':').append(s.port).append(';');
        if (key.toString().equals(shown)) return;
        shown = key.toString();
        list.removeAllViews();
        hint.setVisibility(screens.isEmpty() ? View.VISIBLE : View.GONE);
        for (Discovery.Screen s : screens) {
            LinearLayout r = Ui.row(this);
            int p = Ui.dp(this, 16);
            r.setPadding(p, p, p, p);
            Ui.cardStyle(r, Ui.CARD);
            TextView ic = Ui.text(this, Ui.icon(s.kind), 28, Ui.TEXT, false);
            r.addView(ic);
            LinearLayout texts = Ui.column(this);
            texts.setPadding(Ui.dp(this, 14), 0, 0, 0);
            texts.addView(Ui.text(this, s.name, 18, Ui.TEXT, true));
            texts.addView(Ui.text(this, kindLabel(s.kind) + " · " + s.host, 13, Ui.MUTED, false));
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setOnClickListener(v -> begin(s));
            list.addView(r, Ui.lp(-1, -2, Ui.dp(this, 10)));
        }
    }

    private static String kindLabel(String k) {
        switch (k == null ? "" : k) {
            case "tv": return "TV";
            case "pc": return "Computer";
            case "tablet": return "Tablet";
            default: return "Phone";
        }
    }

    private void manualAddress() {
        EditText e = new EditText(this);
        e.setHint("192.168.1.20");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Address shown on the receiving screen")
                .setView(e)
                .setPositiveButton("Cast", (d, w) -> {
                    String a = e.getText().toString().trim();
                    if (a.isEmpty()) return;
                    Discovery.Screen s = new Discovery.Screen();
                    s.port = Proto.STREAM_PORT;
                    int colon = a.lastIndexOf(':');
                    if (colon > 0) {
                        try { s.port = Integer.parseInt(a.substring(colon + 1)); a = a.substring(0, colon); }
                        catch (NumberFormatException ignored) {}
                    }
                    s.host = a; s.name = a; s.kind = "screen";
                    begin(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void begin(Discovery.Screen s) {
        pending = s;
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (prefs.sound() && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        askCapture();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS && pending != null) askCapture();
    }

    private void askCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE || pending == null) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Casting needs permission to show your screen", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, CastService.class).setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_CODE, result)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra(CastService.EXTRA_HOST, pending.host)
                .putExtra(CastService.EXTRA_PORT, pending.port)
                .putExtra(CastService.EXTRA_TARGET, pending.name);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        pending = null;
    }

    @Override public void onStatus(CastService.Status s, String target, String message) {
        switch (s) {
            case CONNECTING:
                statusCard.setVisibility(View.VISIBLE);
                statusTitle.setText("Connecting to " + target + "…");
                statusSub.setText("");
                break;
            case WAITING:
                statusCard.setVisibility(View.VISIBLE);
                statusTitle.setText("Waiting for " + target);
                statusSub.setText("Press Allow on that screen");
                break;
            case CASTING:
                statusCard.setVisibility(View.VISIBLE);
                statusTitle.setText("Casting to " + target);
                statusSub.setText("Everything on your screen is being shown. Stop here or from the notification.");
                break;
            default:
                statusCard.setVisibility(View.GONE);
                if (message != null && !message.isEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }
}
