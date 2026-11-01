package com.fm1.midibridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * The note popup over other apps (e.g. a Udemy piano lesson on the Fire TV):
 * a badge showing the note or chord being played, also on a grand staff,
 * gone again shortly after the music stops. It never takes focus or
 * touches, so the remote keeps driving the app underneath.
 *
 * Fire OS only shows an overlay window that was added while this app was in
 * front: one added from the background (the service after a restart, or
 * when a note comes) stays invisible however it's set. So the window is
 * added, or added again, whenever MIDI Bridge's screen comes up
 * (MainActivity.onResume -> attachFromForeground()), stays added, and the
 * badge only fades in and out (alpha; never GONE, which also got stuck).
 * After a restart: open MIDI Bridge once.
 *
 * Where it sits and how big it is are the user's choice (setAdjusting(), from
 * MainActivity's "Move / resize" button) and are remembered.
 *
 * Needs "draw over other apps" (SYSTEM_ALERT_WINDOW, declared only in the
 * Fire TV build); without it this does nothing. Run by BridgeService while
 * the bridge is ON; stays hidden while MIDI Bridge's own screen is in front,
 * which has its own big popup (except while adjusting).
 */
class NoteOverlay {

    private static final int ACCENT = Color.rgb(80, 200, 255);
    private static final long LINGER_MS = 1200;
    private static final long CHORD_SETTLE_MS = 100;
    static final float MIN_SCALE = 0.6f, MAX_SCALE = 2.0f;

    /** The running overlay (BridgeService has one while the bridge is ON), for MainActivity's adjust mode. */
    static NoteOverlay current;

    private final Context ctx;
    private final Bridge bridge;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout badge;
    private TextView name, detail;
    private StaffView staff;
    private boolean shown, adjusting, attached;
    private long lastNoteAt;
    private float scale;
    // What's on show, so a resize (which rebuilds the badge) can put it back.
    private int[] shownNotes;
    private String shownChord;

    // Same chord rule as MainActivity's ticker: name it once the keys stay put.
    private long seenNoteOns = -1;
    private boolean chordArmed;
    private int[] settlingKeys = new int[0];
    private long settlingSince;

    NoteOverlay(Context ctx) {
        this.ctx = ctx;
        this.bridge = Bridge.get(ctx);
        this.prefs = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE);
    }

    static boolean allowed(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    void start() {
        if (badge != null) return;
        if (!allowed(ctx)) {
            Log.i("MidiBridge", "note overlay off: no \"draw over other apps\" permission");
            return;
        }
        wm = ctx.getSystemService(WindowManager.class);
        scale = prefs.getFloat("scale", 1f);
        buildBadge();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,  // Fire OS 6 = Android 7.1
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;  // x, y = from the top-right corner
        lp.x = prefs.getInt("x", dp(32));
        lp.y = prefs.getInt("y", dp(32));
        attach();  // usually started from MIDI Bridge's screen, so this one shows
        Log.i("MidiBridge", "note overlay on");
        current = this;
        seenNoteOns = bridge.noteOnCount();  // don't pop up for a note played before we started
        ui.post(ticker);
    }

    void stop() {
        ui.removeCallbacks(ticker);
        if (current == this) current = null;
        detach();
        badge = null;
        shown = false;
    }

    /** Called from MainActivity.onResume: (re)adds the window while we're in front, so Fire OS shows it. */
    void attachFromForeground() {
        if (badge == null) return;
        detach();
        attach();
    }

    private void attach() {
        if (attached) return;
        try {
            wm.addView(badge, lp);
            attached = true;
        } catch (RuntimeException e) {  // e.g. "draw over other apps" taken away meanwhile
            Log.w("MidiBridge", "note overlay failed", e);
        }
    }

    private void detach() {
        if (!attached) return;
        attached = false;
        try {
            wm.removeView(badge);
        } catch (RuntimeException ignored) {
        }
    }

    private void update() {
        if (attached) wm.updateViewLayout(badge, lp);
    }

    // ---- moving and resizing

    /**
     * Adjust mode: the badge stays up (with the last note, or A4) even over
     * MIDI Bridge's own screen, and can be dragged on a touch screen.
     */
    void setAdjusting(boolean on) {
        if (badge == null || adjusting == on) return;
        adjusting = on;
        if (on) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (shownNotes == null) showNote(bridge.lastNoteNum() >= 0 ? bridge.lastNoteNum() : 69);
            else open();
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lastNoteAt = System.currentTimeMillis();  // then fades out as usual
        }
        update();
    }

    /** Moves the badge (pixels, + = right / down), kept on screen. */
    void moveBy(int dx, int dy) {
        if (badge == null) return;
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        lp.x = Math.max(0, Math.min(sw - badge.getWidth(), lp.x - dx));  // x counts from the right
        lp.y = Math.max(0, Math.min(sh - badge.getHeight(), lp.y + dy));
        update();
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    /** Grows (+) or shrinks (-) the badge by a step; rebuilt at the new size. */
    void scaleBy(float delta) {
        if (badge == null) return;
        float s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.round((scale + delta) * 10) / 10f));
        if (s == scale) return;
        scale = s;
        prefs.edit().putFloat("scale", s).apply();
        boolean wasAttached = attached;
        detach();
        buildBadge();
        if (wasAttached) attach();
        boolean was = shown;
        shown = false;
        if (shownChord != null) showChord(shownNotes, shownChord);
        else if (shownNotes != null) showNote(shownNotes[0]);
        if (!was && !adjusting) close();
    }

    float scale() {
        return scale;
    }

    private void buildBadge() {
        // [grand staff | name over detail]
        badge = new LinearLayout(ctx);
        badge.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 14, 20, 26));
        bg.setStroke(dp(3), ACCENT);
        bg.setCornerRadius(dp(22));
        badge.setBackground(bg);
        badge.setPadding(dp(16), dp(10), dp(16), dp(16));
        badge.setAlpha(0f);
        badge.setOnTouchListener(drag);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        badge.addView(top);

        staff = new StaffView(ctx);
        top.addView(staff, new LinearLayout.LayoutParams(dp(180), dp(200)));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setMinimumWidth(dp(170));
        text.setPadding(0, 0, dp(12), 0);
        top.addView(text);

        name = new TextView(ctx);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setIncludeFontPadding(false);
        name.setGravity(Gravity.CENTER);
        text.addView(name);

        detail = new TextView(ctx);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24 * scale);
        detail.setTextColor(ACCENT);
        detail.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        detail.setGravity(Gravity.CENTER);
        detail.setSingleLine(true);
        text.addView(detail);
    }

    // Touch screens, while adjusting: drag the badge around.
    private final View.OnTouchListener drag = new View.OnTouchListener() {
        private float lastX, lastY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (!adjusting) return false;
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                moveBy(Math.round(e.getRawX() - lastX), Math.round(e.getRawY() - lastY));
            }
            lastX = e.getRawX();
            lastY = e.getRawY();
            return true;
        }
    };

    // ---- showing

    private void showNote(int note) {
        shownNotes = new int[]{note};
        shownChord = null;
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 88 * scale);
        name.setText(Bridge.pitchClass(note));
        staff.setNotes(note);
        detail.setText(String.format(Locale.US, "%s   %.1f Hz", Bridge.noteName(note), Bridge.noteHz(note)));
        open();
    }

    private void showChord(int[] keys, String chord) {
        shownNotes = keys;
        shownChord = chord;
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP,
                scale * (chord.length() <= 4 ? 80 : chord.length() <= 8 ? 60 : 44));
        name.setText(chord);
        staff.setNotes(keys);
        StringBuilder sb = new StringBuilder();
        for (int n : keys) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(Bridge.noteName(n));
        }
        String interval = Chord.interval(keys);
        if (interval != null) sb.append("   \u00B7   ").append(interval);
        detail.setText(sb);
        open();
    }

    private void open() {
        if (shown) return;
        shown = true;
        badge.animate().cancel();
        badge.setAlpha(0f);
        attach();  // no-op if added; first time after a restart it may stay invisible (see above)
        badge.animate().alpha(1f).setDuration(150).start();
    }

    private void close() {
        if (!shown) return;
        shown = false;
        badge.animate().cancel();
        badge.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> {
                    if (!shown && badge != null) badge.setAlpha(0f);
                }).start();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long ons = bridge.noteOnCount();
            if (ons != seenNoteOns) {
                seenNoteOns = ons;
                // The spyglass shows the notes already: one box over the lesson is enough.
                if ((adjusting || !MainActivity.inFront) && !Spyglass.get(ctx).isShowing() && bridge.lastNoteNum() >= 0) {
                    lastNoteAt = System.currentTimeMillis();
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
            if (shown && !adjusting && (MainActivity.inFront || (!bridge.isSounding()
                    && System.currentTimeMillis() - lastNoteAt > LINGER_MS))) {
                close();
            }
            ui.postDelayed(this, 50);
        }
    };

    private int dp(int v) {
        return Math.round(v * scale * ctx.getResources().getDisplayMetrics().density);
    }
}
