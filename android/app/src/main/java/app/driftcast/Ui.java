package app.driftcast;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny view helpers so the app needs no extra libraries. */
public final class Ui {
    private Ui() {}

    public static final int BG = Color.parseColor("#0E1430");
    public static final int CARD = Color.parseColor("#1A2350");
    public static final int CARD_HI = Color.parseColor("#26306A");
    public static final int ACCENT = Color.parseColor("#FF6B5A");
    public static final int TEXT = Color.parseColor("#F3F5FF");
    public static final int MUTED = Color.parseColor("#A3ABD3");
    public static final int GOOD = Color.parseColor("#5BD6A2");

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A focusable, clickable rounded card (works with touch and TV remotes). */
    public static void cardStyle(View v, int color) {
        Context c = v.getContext();
        float r = dp(c, 20);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = round(CARD_HI, r);
        focused.setStroke(dp(c, 3), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, round(color, r));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, round(Color.WHITE, r)));
        v.setFocusable(true);
        v.setClickable(true);
    }

    public static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 16, primary ? Color.WHITE : TEXT, true);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 22), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        cardStyle(b, primary ? ACCENT : CARD);
        return b;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = topMargin;
        return p;
    }

    public static String icon(String kind) {
        if ("tv".equals(kind)) return "📺";
        if ("pc".equals(kind)) return "💻";
        if ("tablet".equals(kind)) return "🖵";
        return "📱";
    }
}
