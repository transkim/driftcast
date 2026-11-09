package app.driftcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Home: two big choices. TVs go straight to Receive. */
public class MainActivity extends Activity {
    private TextView nameView;
    private Prefs prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (Prefs.isTv(this) && b == null && !getIntent().getBooleanExtra("home", false)) {
            startActivity(new Intent(this, ReceiveActivity.class));
            finish();
            return;
        }
        build();
    }

    private void build() {
        int pad = Ui.dp(this, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = Ui.column(this);
        col.setPadding(pad, pad, pad, pad);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(col);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        col.addView(logo, Ui.lp(Ui.dp(this, 88), Ui.dp(this, 88), Ui.dp(this, 24)));

        TextView title = Ui.text(this, "Driftcast", 32, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 12)));
        TextView sub = Ui.text(this, "Show your phone on any screen on your Wi-Fi", 16, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 6)));

        View cast = choice("Cast my screen", "Send this phone to a TV, laptop or another phone", true);
        cast.setOnClickListener(v -> startActivity(new Intent(this, CastActivity.class)));
        col.addView(cast, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)));

        View recv = choice("Receive here", "Show another phone on this screen", false);
        recv.setOnClickListener(v -> startActivity(new Intent(this, ReceiveActivity.class)));
        col.addView(recv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 16)));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        nameView = Ui.text(this, "", 14, Ui.MUTED, false);
        nameView.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 12);
        nameView.setPadding(p, p, p, p);
        nameView.setFocusable(true);
        nameView.setOnClickListener(v -> rename());
        col.addView(nameView, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 24)));
        refreshName();

        setContentView(scroll);
        cast.requestFocus();
    }

    private View choice(String title, String subtitle, boolean primary) {
        LinearLayout card = Ui.column(this);
        int p = Ui.dp(this, 22);
        card.setPadding(p, p, p, p);
        Ui.cardStyle(card, primary ? Ui.ACCENT : Ui.CARD);
        card.addView(Ui.text(this, title, 22, primary ? 0xFFFFFFFF : Ui.TEXT, true));
        TextView s = Ui.text(this, subtitle, 15, primary ? 0xF0FFFFFF : Ui.MUTED, false);
        card.addView(s, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 4)));
        return card;
    }

    private void refreshName() {
        nameView.setText("This device is called “" + prefs.name() + "”  ·  Rename");
    }

    private void rename() {
        EditText e = new EditText(this);
        e.setText(prefs.name());
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Name shown to other devices")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> { prefs.setName(e.getText().toString()); refreshName(); })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
