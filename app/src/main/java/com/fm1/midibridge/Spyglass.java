package com.fm1.midibridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * The spyglass: the last note or chord in a round, see-through window that
 * floats over every app, e.g. Skoove or a YouTube lesson. Big by default.
 * Drag it to move it; pinch it, or drag the grip on its rim, to resize it.
 * Tap it to show the grip and the close button for a few seconds.
 *
 * Unlike NoteOverlay's badge it never fades: it always shows the last thing
 * played. Turned on and off with MainActivity's "Spyglass" button; where it
 * is, its size and whether it's on are remembered. Needs "draw over other
 * apps" (SYSTEM_ALERT_WINDOW).
 */
class Spyglass {

    private static final int ACCENT = Color.rgb(80, 200, 255);
    private static final int DIM = Color.rgb(110, 120, 130);
    // The brass eyepiece.
    private static final int BRASS = Color.rgb(0xc9, 0x9a, 0x45);
    private static final int BRASS_LIGHT = Color.rgb(0xf3, 0xd6, 0x8a);
    private static final int BRASS_DARK = Color.rgb(0x8a, 0x62, 0x24);
    private static final int BRASS_SHADOW = Color.rgb(0x3e, 0x2a, 0x0e);
    private static final long CHORD_SETTLE_MS = 100;
    private static final long HANDLES_MS = 4000;

    private static Spyglass instance;

    static Spyglass get(Context ctx) {
        if (instance == null) instance = new Spyglass(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Bridge bridge;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private GlassView glass;
    private TextView close, grip;
    private StaffView staff;
    private int size;  // diameter, pixels

    // What's on show: one note, or a chord's keys and name.
    private int[] shownNotes;
    private String shownChord;
    private int shownVel = -1;

    // The FM-1's sound, while it's plugged into the tablet and listening is allowed.
    private Scope scope;
    private long scopeLookedAt;
    private float[] wave;

    // Same chord rule as MainActivity's ticker: name it once the keys stay put.
    private long seenNoteOns = -1;
    private boolean chordArmed;
    private int[] settlingKeys = new int[0];
    private long settlingSince;

    private Spyglass(Context ctx) {
        this.ctx = ctx;
        this.bridge = Bridge.get(ctx);
        this.prefs = ctx.getSharedPreferences("spyglass", Context.MODE_PRIVATE);
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    static boolean allowed(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    boolean isOn() {
        return prefs.getBoolean("on", false);
    }

    boolean isShowing() {
        return glass != null;
    }

    void setOn(boolean on) {
        prefs.edit().putBoolean("on", on).apply();
        sync();
    }

    /** Shows it if it's on and allowed, takes it away if not. Safe to call any time. */
    void sync() {
        if (isOn() && allowed(ctx)) start();
        else stop();
    }

    private void start() {
        if (glass != null) return;
        wm = ctx.getSystemService(WindowManager.class);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        size = clampSize(prefs.getInt("size", Math.round(300 * density)));  // big: easy to read
        build();
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,  // Fire OS 6 = Android 7.1
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dm.widthPixels - size - Math.round(24 * density));
        lp.y = prefs.getInt("y", Math.round(80 * density));
        try {
            wm.addView(glass, lp);
        } catch (RuntimeException e) {  // e.g. "draw over other apps" taken away meanwhile
            Log.w("MidiBridge", "spyglass failed", e);
            glass = null;
            return;
        }
        if (bridge.lastNoteNum() >= 0) showNote(bridge.lastNoteNum());
        else showNothing();
        seenNoteOns = bridge.noteOnCount();
        ui.post(ticker);
        Log.i("MidiBridge", "spyglass on");
    }

    private void stop() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideHandles);
        if (scope != null) scope.close();
        scope = null;
        wave = null;
        if (glass == null) return;
        try {
            wm.removeView(glass);
        } catch (RuntimeException ignored) {
        }
        glass = null;
    }

    private int clampSize(int px) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int max = Math.min(dm.widthPixels, dm.heightPixels);
        return Math.max(Math.round(100 * density), Math.min(max, px));
    }

    // ---- the window

    private void build() {
        glass = new GlassView(ctx);
        staff = new StaffView(ctx);
        glass.addView(staff);

        close = handle("✕");
        close.setOnClickListener(v -> setOn(false));
        grip = handle("↔");
        grip.setRotation(45);  // a diagonal two-headed arrow
        grip.setOnTouchListener(resize);

        glass.setOnTouchListener(move);
        layout();
    }

    private TextView handle(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setVisibility(View.GONE);
        glass.addView(t);
        return t;
    }

    /** Sizes everything to the diameter. */
    private void layout() {
        placeStaff();

        int h = Math.round(size * 0.12f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(h, h, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = Math.round(size * 0.035f);
        close.setLayoutParams(clp);
        close.setBackground(circle(Color.rgb(28, 28, 30), Color.rgb(90, 90, 90)));
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.5f);

        int g = Math.round(size * 0.14f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(g, g, Gravity.BOTTOM | Gravity.END);
        glp.rightMargin = glp.bottomMargin = Math.round(size * 0.08f);
        grip.setLayoutParams(glp);
        grip.setBackground(circle(BRASS, BRASS_SHADOW));
        grip.setTextColor(BRASS_SHADOW);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_PX, g * 0.6f);
        glass.invalidate();
    }

    /** The staff in the middle; to the left, with the waveform to its right, while the FM-1 is heard. */
    private void placeStaff() {
        boolean side = hearing();
        int w = Math.round(size * (side ? 0.29f : 0.34f)), h = Math.round(w * 1.06f);
        float cx = size / 2f + (side ? -size * 0.17f : 0), cy = size / 2f + size * (side ? 0.1f : 0.11f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        slp.leftMargin = Math.round(cx - w / 2f);
        slp.topMargin = Math.round(cy - h / 2f);
        staff.setLayoutParams(slp);
    }

    private boolean hearing() {
        return scope != null && scope.listening();
    }

    private GradientDrawable circle(int fill, int ring) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setStroke(Math.round(2 * density), ring);
        return d;
    }

    private void resizeTo(int px) {
        px = clampSize(px);
        if (px == size || glass == null) return;
        size = px;
        lp.width = lp.height = px;
        layout();
        wm.updateViewLayout(glass, lp);
    }

    private void save() {
        prefs.edit().putInt("size", size).putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    // ---- touch: drag to move, pinch to resize, tap for the handles

    private ScaleGestureDetector pinchDetector;

    private ScaleGestureDetector pinch() {
        if (pinchDetector == null) {
            pinchDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    resizeTo(Math.round(size * d.getScaleFactor()));
                    return true;
                }
            });
        }
        return pinchDetector;
    }

    private final View.OnTouchListener move = new View.OnTouchListener() {
        private float lastX, lastY, downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            pinch().onTouchEvent(e);
            if (e.getPointerCount() > 1) {
                dragged = true;  // a pinch is never a tap
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    dragged = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
                    if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragged = true;
                    if (dragged) {
                        lp.x += Math.round(e.getRawX() - lastX);
                        lp.y += Math.round(e.getRawY() - lastY);
                        wm.updateViewLayout(glass, lp);
                    }
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    if (!dragged) toggleHandles();
                    save();
                    break;
            }
            return true;
        }
    };

    private final View.OnTouchListener resize = new View.OnTouchListener() {
        private float lastX, lastY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ui.removeCallbacks(hideHandles);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // The window grows from its top-left corner, so the grip follows the finger.
                    float d = ((e.getRawX() - lastX) + (e.getRawY() - lastY)) / 2f;
                    resizeTo(Math.round(size + d / 0.9f));
                    break;
                case MotionEvent.ACTION_UP:
                    save();
                    ui.postDelayed(hideHandles, HANDLES_MS);
                    break;
            }
            lastX = e.getRawX();
            lastY = e.getRawY();
            return true;
        }
    };

    private void toggleHandles() {
        boolean show = close.getVisibility() != View.VISIBLE;
        close.setVisibility(show ? View.VISIBLE : View.GONE);
        grip.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideHandles);
        if (show) ui.postDelayed(hideHandles, HANDLES_MS);
    }

    private final Runnable hideHandles = () -> {
        if (glass == null) return;
        close.setVisibility(View.GONE);
        grip.setVisibility(View.GONE);
    };

    // ---- showing

    private void showNothing() {
        shownNotes = null;
        shownChord = null;
        shownVel = -1;
        staff.setVisibility(View.GONE);
        glass.invalidate();
    }

    private void showNote(int note) {
        shownNotes = new int[]{note};
        shownChord = null;
        shownVel = bridge.lastVelocity();
        staff.setVisibility(View.VISIBLE);
        staff.setNotes(note);
        glass.invalidate();
    }

    private void showChord(int[] keys, String chord) {
        shownNotes = keys;
        shownChord = chord;
        shownVel = -1;
        staff.setVisibility(View.VISIBLE);
        staff.setNotes(keys);
        glass.invalidate();
    }

    /** The FM-1's sound: open it when it's plugged in (looked for every 2 s), and keep the wave fresh. */
    private void listen() {
        long now = System.currentTimeMillis();
        if ((scope == null || scope.isDead()) && now - scopeLookedAt > 2000) {
            scopeLookedAt = now;
            scope = Scope.open(ctx);
        }
        boolean before = staffBeside;
        float[] w = null;
        if (hearing() && shownNotes != null) w = scope.wave(Bridge.noteHz(shownNotes[0]));
        staffBeside = hearing();
        if (staffBeside != before) placeStaff();
        if (w != null || wave != null || staffBeside != before) {
            wave = w;
            glass.invalidate();
        }
    }

    private boolean staffBeside;

    /** The glass: the eyepiece, the label, the note or chord name, the wave, the details. The staff is a child view. */
    private class GlassView extends FrameLayout {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        private final RectF r = new RectF();

        GlassView(Context c) {
            super(c);
            setWillNotDraw(false);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
            lens(c, cx, cy, d);
            boolean listening = staffBeside;
            if (listening) label(c, "♪ FM-1", cx, cy - d * 0.345f, d * 0.045f, d * 0.42f, BRASS_LIGHT);
            String name, detail;
            if (shownNotes == null) {
                name = "♪";
                detail = "play a note";
            } else if (shownChord != null) {
                name = shownChord;
                StringBuilder sb = new StringBuilder();
                for (int n : shownNotes) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(Bridge.noteName(n));
                }
                String interval = Chord.interval(shownNotes);
                if (interval != null) sb.append("   ·   ").append(interval);
                detail = sb.toString();
            } else {
                int n = shownNotes[0];
                name = Bridge.pitchClass(n);
                detail = String.format(Locale.US, "%s   %.0f Hz", Bridge.noteName(n), Bridge.noteHz(n));
                if (shownVel > 0) detail += "   vel " + shownVel;
            }
            label(c, name, cx, cy - d * 0.195f, shownChord != null ? d * 0.19f : d * 0.24f, d * 0.72f, Color.WHITE);
            if (listening) {
                // The FM-1's real waveform, right of the staff; a flat grey line while it's quiet.
                r.set(cx + d * 0.02f, cy + d * 0.0f, cx + d * 0.32f, cy + d * 0.2f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2 * density, d * 0.006f));
                p.setStrokeJoin(Paint.Join.ROUND);
                float[] w = wave;
                line.reset();
                if (w != null) {
                    p.setColor(ACCENT);
                    for (int i = 0; i < w.length; i++) {
                        float x = r.left + r.width() * i / (w.length - 1), y = r.centerY() - w[i] * r.height() / 2f;
                        if (i == 0) line.moveTo(x, y);
                        else line.lineTo(x, y);
                    }
                } else {
                    p.setColor(DIM);
                    line.moveTo(r.left, r.centerY());
                    line.lineTo(r.right, r.centerY());
                }
                c.drawPath(line, p);
            }
            label(c, detail, cx, cy + d * 0.325f, Math.max(13 * density, d * 0.065f), d * 0.54f, Color.WHITE);
            if (shownChord == null && shownVel > 0) {
                // How hard the key was hit: a short brass bar under it, full at 127.
                float w = d * 0.26f, h = Math.max(3 * density, d * 0.012f), top = cy + d * 0.37f - h / 2;
                p.setStyle(Paint.Style.FILL);
                p.setColor(BRASS_SHADOW);
                r.set(cx - w / 2, top, cx + w / 2, top + h);
                c.drawRoundRect(r, h / 2, h / 2, p);
                p.setColor(BRASS_LIGHT);
                r.set(cx - w / 2, top, cx - w / 2 + w * shownVel / 127f, top + h);
                c.drawRoundRect(r, h / 2, h / 2, p);
            }
        }

        /** Text centred on (x, y), smaller if it wouldn't fit in `room`. */
        private void label(Canvas c, String s, float x, float y, float size, float room, int color) {
            text.setTextSize(size);
            float w = text.measureText(s);
            if (w > room) text.setTextSize(size * room / w);
            text.setColor(color);
            Paint.FontMetrics m = text.getFontMetrics();
            c.drawText(s, x, y - (m.ascent + m.descent) / 2, text);
        }

        /**
         * A spyglass's eyepiece: see-through dark glass, darker toward the edge, a glint of light,
         * and a knurled brass rim lit from the top left (Synth Spyglass's lens() on the Mac).
         */
        private void lens(Canvas c, float cx, float cy, float d) {
            float r = d / 2, rim = Math.max(7 * density, d * 0.05f), gr = r - rim;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, gr, new int[]{Color.argb(200, 0x0e, 0x14, 0x1a), Color.argb(200, 0x0e, 0x14, 0x1a), Color.argb(235, 4, 6, 9)},
                    new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, gr, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            // A glint on the glass, top left.
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(d * 0.012f);
            p.setColor(Color.argb(22, 255, 255, 255));
            r(cx, cy, gr * 0.94f);
            c.drawArc(this.r, 205, 32, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            // The brass rim: bands from dark to light and back, so it looks rounded.
            int[] bands = {BRASS_SHADOW, BRASS_DARK, BRASS, BRASS_LIGHT, BRASS, BRASS_DARK};
            float bw = rim / bands.length;
            p.setStrokeWidth(bw + 0.6f);
            for (int i = 0; i < bands.length; i++) {
                p.setColor(bands[i]);
                c.drawCircle(cx, cy, gr + bw * (i + 0.5f), p);
            }
            // Light from the top left, shade at the bottom right.
            p.setStrokeWidth(rim * 0.35f);
            p.setColor(Color.argb(70, 255, 240, 200));
            r(cx, cy, r - rim * 0.45f);
            c.drawArc(this.r, 198, 90, false, p);
            p.setStrokeWidth(rim * 0.5f);
            p.setColor(Color.argb(90, 40, 25, 5));
            r(cx, cy, r - rim * 0.5f);
            c.drawArc(this.r, 18, 90, false, p);
            // Knurling on the outer edge.
            int ticks = (int) ((r * 2 * Math.PI) / Math.max(5 * density, d * 0.025f));
            p.setStrokeWidth(Math.max(1, d * 0.004f));
            p.setColor(Color.argb(130, 40, 25, 5));
            for (int k = 0; k < ticks; k++) {
                double a = 2 * Math.PI * k / ticks;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                c.drawLine(cx + dx * (r - rim * 0.42f), cy + dy * (r - rim * 0.42f), cx + dx * (r - 0.5f), cy + dy * (r - 0.5f), p);
            }
            // A fine dark line where the glass meets the brass, and the outer edge.
            p.setColor(BRASS_SHADOW);
            p.setStrokeWidth(Math.max(1, d * 0.004f));
            c.drawCircle(cx, cy, gr, p);
            p.setStrokeWidth(1);
            c.drawCircle(cx, cy, r - 0.5f, p);
        }

        private void r(float cx, float cy, float radius) {
            r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (glass == null) return;
            listen();
            long ons = bridge.noteOnCount();
            if (ons != seenNoteOns) {
                seenNoteOns = ons;
                if (bridge.lastNoteNum() >= 0) {
                    showNote(bridge.lastNoteNum());
                    chordArmed = true;
                }
            }
            if (chordArmed) {
                int[] keys = bridge.keysDown();
                long now = System.currentTimeMillis();
                if (!Arrays.equals(keys, settlingKeys)) {
                    settlingKeys = keys;
                    settlingSince = now;
                } else if (now - settlingSince >= CHORD_SETTLE_MS + (Chord.isPair(keys) ? Chord.PAIR_EXTRA_MS : 0)) {
                    chordArmed = false;
                    String chord = Chord.name(keys);
                    if (chord != null) showChord(keys, chord);
                }
            }
            ui.postDelayed(this, 50);
        }
    };
}
