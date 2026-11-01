package com.fm1.midibridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The screen: shows and controls the Bridge (see Bridge.java), which does
 * the actual forwarding and keeps running in BridgeService after you leave
 * this screen for another app.
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Bridge bridge;

    private Button fromBtn, toBtn;
    private Button toggle;
    private TextView status, lastMsg, bigNote;
    private Button panic;
    private Button spyglassBtn;
    private boolean wantSpyglass;  // sent to the "draw over other apps" page for it
    // Big note panel: pops up over everything while you play, folds back into the NOTE card after.
    private FrameLayout notePopup;
    private LinearLayout notePanel;
    private TextView popName, popOctave, popHz, popNotes;
    private StaffView popStaff;
    private LinearLayout popRow;
    // Chord on show in the NOTE card ("C major\nC4  E4  G4"), or null for the single note.
    private String chordText;
    private boolean popupShown;
    private long lastNoteAt;
    private final Runnable onChange = this::refresh;
    // This screen is in front: NoteOverlay keeps out of the way of our own popup.
    // Resumed/paused, not started/stopped: after Back, onStop can come seconds
    // late on Fire OS (it waits for the next app to go idle), and the box over
    // that app stayed hidden until then.
    static volatile boolean inFront;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = Bridge.get(this);
        buildUi();
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) showRemoteFocus();
        if (!bridge.hasMidi()) toggle.setEnabled(false);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        // Was ON last time (e.g. the app was closed or the tablet restarted): carry on.
        if (bridge.isOn()) bridge.setOn(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bridge.addListener(onChange);
        refresh();
        ui.post(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        if (NoteOverlay.current != null) NoteOverlay.current.attachFromForeground();
        Spyglass glass = Spyglass.get(this);
        // Back from the "draw over other apps" settings page: it was asked for, so turn it on.
        if (wantSpyglass && Spyglass.allowed(this)) glass.setOn(true);
        wantSpyglass = false;
        glass.sync();
        if (glass.isShowing()) askToHear();
        if (spyglassBtn != null) updateSpyglassButton();
    }

    /**
     * With the FM-1 plugged in, the spyglass can show its real waveform: Android lets an app hear
     * a USB audio input only with the microphone permission, so ask (once it's on, while it's in use).
     */
    private void askToHear() {
        if (Build.VERSION.SDK_INT >= 23 && !Scope.allowed(this) && Scope.findDevice(this) != null
                && !getSharedPreferences("spyglass", MODE_PRIVATE).getBoolean("asked_to_hear", false)) {
            getSharedPreferences("spyglass", MODE_PRIVATE).edit().putBoolean("asked_to_hear", true).apply();
            Toast.makeText(this, "Allow Synth Spyglass to hear the FM-1's sound, for the spyglass's waveform.", Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        // Allowed to hear the FM-1: the service takes on the microphone type, so the spyglass
        // keeps hearing it over other apps.
        if (code == 2 && Scope.allowed(this) && bridge.isOn()) bridge.setOn(true);
    }

    @Override
    protected void onPause() {
        inFront = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Only the screen stops updating — the bridge itself keeps running.
        bridge.removeListener(onChange);
        ui.removeCallbacks(ticker);
        popupShown = false;
        notePopup.setVisibility(View.GONE);
        super.onStop();
    }

    /**
     * Cheap tablets often kill background apps to save battery even when they
     * have a foreground service. Ask once to be exempt ("Unrestricted").
     */
    private void askToRunInBackground() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void refresh() {
        Bridge.PortRef from = bridge.from();
        Bridge.PortRef to = bridge.to();
        fromBtn.setText((from != null ? from.label : "(no keyboard found)") + "   ▾");
        if (bridge.fromIsSilent()) {
            // Nothing can come out of the FM-1, so every TO is pointless: grey it out.
            toBtn.setText("Nothing to send — the FM-1 doesn't send MIDI   ⓘ");
            toBtn.setTextColor(Color.rgb(130, 130, 130));
            toBtn.setBackground(rounded(Color.rgb(16, 16, 16), Color.rgb(50, 50, 50), 12));
        } else {
            toBtn.setText((to != null ? to.label : "(nothing found — tap to see devices)") + "   ▾");
            toBtn.setTextColor(TEXT);
            toBtn.setBackground(rounded(Color.rgb(28, 28, 30), BORDER, 12));
        }
        status.setText(bridge.status());
        updateToggle();
    }

    /** Tapping the greyed-out TO says why, and offers the fix. */
    private void explainSilentFrom() {
        new AlertDialog.Builder(this).setTitle("Nothing to send")
                .setMessage(Bridge.SILENT_FROM)
                .setPositiveButton("Choose FROM", (d, w) -> pickPort(true))
                .setNegativeButton("OK", null)
                .show();
    }

    /** Big-text device list in a dialog — works at any font size, unlike a Spinner. */
    private void pickPort(boolean isFrom) {
        List<Bridge.PortRef> items = isFrom ? bridge.sources() : bridge.dests();
        String title = isFrom ? "FROM — the keyboard you play" : "TO — the FM-1 (or MIDI interface)";
        if (items.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(isFrom || bridge.sources().isEmpty()
                            ? "No MIDI devices found. Check the hub and cables."
                            : bridge.fromIsWifi()
                            ? "Nothing to play on here, so the computer's notes are just shown. "
                            + "To hear them on the FM-1, plug it in here or tick it in the computer's MIDI Bridge."
                            : "Only the keyboard is plugged in. The tablet doesn't see the FM-1 — "
                            + "check its USB cable, that it's switched on, and the hub's power.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        ArrayAdapter<Bridge.PortRef> ad = new ArrayAdapter<Bridge.PortRef>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextSize(20);
                t.setSingleLine(false);
                t.setTextColor(TEXT);
                t.setPadding(dp(20), dp(16), dp(20), dp(16));
                return t;
            }
        };
        new AlertDialog.Builder(this).setTitle(title)
                .setAdapter(ad, (d, which) -> {
                    Bridge.PortRef picked = items.get(which);
                    if (isFrom) bridge.choose(picked, bridge.to());
                    else bridge.choose(bridge.from(), picked);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Palette: white on black, high contrast. Every color is set explicitly so
    // the tablet's own theme can't produce grey-on-grey text.
    private static final int BG = Color.BLACK;
    private static final int CARD = Color.BLACK;
    private static final int BORDER = Color.rgb(90, 90, 90);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(200, 200, 200);
    private static final int ON_GREEN = Color.rgb(0, 150, 60);
    private static final int OFF_GREY = Color.rgb(60, 60, 60);

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(page);

        // One centered column, at most 640dp wide so it doesn't sprawl on a tablet.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int screen = getResources().getDisplayMetrics().widthPixels;
        root.setLayoutParams(new LinearLayout.LayoutParams(Math.min(screen - dp(32), dp(640)),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.setPadding(0, dp(32), 0, dp(32));
        page.addView(root);

        TextView title = text(getApplicationInfo().loadLabel(getPackageManager()).toString(), 30, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);
        TextView subtitle = text("Keyboard → FM-1      v" + versionName(), 16, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        LinearLayout devices = card();
        devices.addView(label("FROM  ·  keyboard"));
        fromBtn = pickerButton(v -> pickPort(true));
        devices.addView(fromBtn);
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        divider.setLayoutParams(dlp);
        devices.addView(divider);
        devices.addView(label("TO  ·  FM-1 (USB) or MIDI interface"));
        toBtn = pickerButton(v -> {
            if (bridge.fromIsSilent()) explainSilentFrom();
            else pickPort(false);
        });
        devices.addView(toBtn);
        root.addView(devices);

        toggle = new Button(this);
        toggle.setTextSize(22);
        toggle.setAllCaps(false);
        toggle.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        lp.topMargin = dp(20);
        toggle.setLayoutParams(lp);
        toggle.setOnClickListener(v -> {
            boolean want = !bridge.isOn();
            if (want) askToRunInBackground();
            bridge.setOn(want);
        });
        root.addView(toggle);

        TextView bgNote = text("Keeps running when you switch apps or the screen turns off. "
                + "Stop it here or from the notification.", 14, MUTED);
        bgNote.setPadding(0, dp(8), 0, 0);
        root.addView(bgNote);

        panic = new Button(this);
        panic.setText("All notes off");
        panic.setAllCaps(false);
        panic.setTextSize(17);
        panic.setTextColor(TEXT);
        panic.setStateListAnimator(null);
        panic.setBackground(rounded(CARD, BORDER, 14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        plp.topMargin = dp(12);
        panic.setLayoutParams(plp);
        panic.setOnClickListener(v -> {
            bridge.allNotesOff();
            updatePanic();
        });
        root.addView(panic);
        updatePanic();

        // The notes in a round window over every app (Skoove, YouTube…).
        spyglassBtn = new Button(this);
        spyglassBtn.setAllCaps(false);
        spyglassBtn.setTextSize(17);
        spyglassBtn.setStateListAnimator(null);
        spyglassBtn.setBackground(rounded(CARD, BORDER, 14));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        glp.topMargin = dp(12);
        spyglassBtn.setLayoutParams(glp);
        spyglassBtn.setOnClickListener(v -> toggleSpyglass());
        root.addView(spyglassBtn);
        updateSpyglassButton();

        // The badge over other apps (Fire TV build): put it where the lesson isn't.
        if (NoteOverlay.allowed(this)) {
            Button place = new Button(this);
            place.setText("Move / resize the note box over other apps");
            place.setAllCaps(false);
            place.setTextSize(17);
            place.setTextColor(TEXT);
            place.setStateListAnimator(null);
            place.setBackground(rounded(CARD, BORDER, 14));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
            mlp.topMargin = dp(12);
            place.setLayoutParams(mlp);
            place.setOnClickListener(v -> adjustOverlay());
            root.addView(place);
        }

        LinearLayout info = card();
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(20);
        info.setLayoutParams(ilp);
        info.addView(label("STATUS"));
        status = text("", 20, TEXT);
        status.setLineSpacing(0, 1.15f);
        info.addView(status);
        info.addView(label("NOTE"));
        bigNote = text("—", 44, TEXT);
        bigNote.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        info.addView(bigNote);
        info.addView(label("LAST MESSAGE"));
        lastMsg = text("—", 20, TEXT);
        lastMsg.setTypeface(Typeface.MONOSPACE);
        lastMsg.setLineSpacing(0, 1.15f);
        info.addView(lastMsg);
        root.addView(info);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.addView(scroll);
        buildNotePopup(frame);
        setContentView(frame);
    }

    private static final int ACCENT = Color.rgb(80, 200, 255);
    private static final long POPUP_LINGER_MS = 1200;
    private static final long CHORD_SETTLE_MS = 100;

    private void buildNotePopup(FrameLayout frame) {
        notePopup = new FrameLayout(this);
        notePopup.setBackgroundColor(Color.argb(200, 0, 0, 0));
        notePopup.setVisibility(View.GONE);
        notePopup.setOnClickListener(v -> hideNotePopup());  // tap anywhere to close early
        frame.addView(notePopup, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        notePanel = new LinearLayout(this);
        notePanel.setOrientation(LinearLayout.VERTICAL);
        notePanel.setGravity(Gravity.CENTER_HORIZONTAL);
        notePanel.setBackground(rounded(Color.rgb(14, 20, 26), ACCENT, 28));
        notePanel.setPadding(dp(24), dp(16), dp(24), dp(24));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(screenW - dp(32), dp(720)), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        notePopup.addView(notePanel, lp);

        // Sizes in dp, not sp: already huge, so the tablet's big-font setting mustn't push them off screen.
        popName = new TextView(this);
        popName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 150);
        popName.setTextColor(TEXT);
        popName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        popName.setGravity(Gravity.CENTER);
        popName.setIncludeFontPadding(false);
        // The name, with the note(s) written on a grand staff beside it.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        notePanel.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(popName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        popStaff = new StaffView(this);
        top.addView(popStaff, new LinearLayout.LayoutParams(dp(190), dp(180)));

        // Chords only: the held keys under the chord name, e.g. "C4   E4   G4".
        popNotes = new TextView(this);
        popNotes.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
        popNotes.setTextColor(ACCENT);
        popNotes.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        popNotes.setGravity(Gravity.CENTER);
        popNotes.setVisibility(View.GONE);
        notePanel.addView(popNotes);

        LinearLayout row = popRow = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        notePanel.addView(row, rlp);
        popOctave = popupTile(row, "OCTAVE");
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));
        popHz = popupTile(row, "FREQUENCY");

        notePanel.setClickable(true);  // taps on the panel itself don't close it; outside does
    }

    /**
     * Places the badge that shows notes over other apps: the remote's arrows
     * move it, rewind / fast-forward make it smaller / bigger, OK or Back is
     * done. On a touch screen: drag it, and use the buttons.
     */
    private void toggleSpyglass() {
        Spyglass glass = Spyglass.get(this);
        if (glass.isShowing()) {
            glass.setOn(false);
        } else if (Spyglass.allowed(this)) {
            glass.setOn(true);
            askToHear();
        } else {
            // Android asks on its own settings page; onResume turns it on once allowed.
            wantSpyglass = true;
            Toast.makeText(this, "Turn on \"Allow display over other apps\" for Synth Spyglass, then come back.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        updateSpyglassButton();
    }

    private void updateSpyglassButton() {
        boolean on = Spyglass.get(this).isShowing();
        spyglassBtn.setText(on ? "◉  Spyglass on — tap to put it away"
                : "◉  Spyglass — the notes in a round window over every app");
        spyglassBtn.setTextColor(on ? ACCENT : TEXT);
    }

    private void adjustOverlay() {
        NoteOverlay o = NoteOverlay.current;
        if (o == null) {
            new AlertDialog.Builder(this).setTitle("Turn the bridge ON first")
                    .setMessage("The note box shows while the bridge is on.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        o.setAdjusting(true);
        int step = dp(24);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Move / resize the note box")
                .setMessage("◀ ▲ ▶ ▼  arrows: move it\n"
                        + "⏪  rewind: smaller     ⏩  fast-forward: bigger\n"
                        + "OK or Back: done\n\n"
                        + "Touch screen: drag the box, and use the buttons.")
                .setNegativeButton("Smaller", null)
                .setNeutralButton("Bigger", null)
                .setPositiveButton("Done", null)
                .create();
        d.setOnKeyListener((di, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: o.moveBy(-step, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: o.moveBy(step, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: o.moveBy(0, -step); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: o.moveBy(0, step); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND: o.scaleBy(-0.1f); return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: o.scaleBy(0.1f); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    di.dismiss();
                    return true;
                default: return false;
            }
        });
        d.setOnDismissListener(di -> o.setAdjusting(false));
        d.show();
        // Bottom-left, out of the way of the box (which starts top-right), and no dimming.
        // Set after show(): Fire OS's dialog theme put it back in the middle otherwise.
        android.view.WindowManager.LayoutParams wl = d.getWindow().getAttributes();
        wl.gravity = Gravity.BOTTOM | Gravity.START;
        wl.width = Math.min(getResources().getDisplayMetrics().widthPixels / 2, dp(520));
        wl.dimAmount = 0f;
        d.getWindow().setAttributes(wl);
        // Smaller / Bigger keep the dialog open.
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> o.scaleBy(-0.1f));
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> o.scaleBy(0.1f));
    }

    /** One half of the popup's bottom row: small caption over a big value. Returns the value view. */
    private TextView popupTile(LinearLayout row, String caption) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setBackground(rounded(Color.BLACK, BORDER, 18));
        tile.setPadding(dp(12), dp(10), dp(12), dp(14));
        row.addView(tile, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        cap.setTextColor(ACCENT);
        cap.setLetterSpacing(0.1f);
        cap.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tile.addView(cap);
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 56);
        value.setTextColor(TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setSingleLine(true);
        tile.addView(value);
        return value;
    }

    /** Where the NOTE card's text sits, relative to the panel's resting place — the panel grows from / shrinks into it. */
    private float[] offsetToNoteCard() {
        int[] a = new int[2], b = new int[2];
        bigNote.getLocationOnScreen(a);
        notePanel.getLocationOnScreen(b);
        float dx = (a[0] + bigNote.getWidth() / 2f) - (b[0] - notePanel.getTranslationX() + notePanel.getWidth() / 2f);
        float dy = (a[1] + bigNote.getHeight() / 2f) - (b[1] - notePanel.getTranslationY() + notePanel.getHeight() / 2f);
        return new float[]{dx, dy};
    }

    private void showNote(int note) {
        popName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 150);
        popName.setText(Bridge.pitchClass(note));
        popStaff.setNotes(note);
        popNotes.setVisibility(View.GONE);
        popRow.setVisibility(View.VISIBLE);
        popOctave.setText(String.valueOf(note / 12 - 1));
        popHz.setText(String.format(Locale.US, "%.1f Hz", Bridge.noteHz(note)));
        openNotePopup();
    }

    /** A known chord (or a two-note interval): its name big, the held keys (and interval) under it. */
    private void showChord(int[] keys, String name) {
        // Shrink long names ("C#m(maj7) / E") so they stay on one line.
        popName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, name.length() <= 4 ? 130 : name.length() <= 8 ? 96 : 64);
        popName.setText(name);
        popStaff.setNotes(keys);
        String interval = Chord.interval(keys);
        popNotes.setText(noteList(keys, "   ") + (interval != null ? "   \u00B7   " + interval : ""));
        popNotes.setVisibility(View.VISIBLE);
        popRow.setVisibility(View.GONE);
        openNotePopup();
    }

    private static String noteList(int[] notes, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int n : notes) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(Bridge.noteName(n));
        }
        return sb.toString();
    }

    private void openNotePopup() {
        if (!popupShown) {
            popupShown = true;
            notePopup.setVisibility(View.VISIBLE);
            notePopup.setAlpha(0f);
            notePopup.animate().alpha(1f).setDuration(150).start();
            notePanel.animate().cancel();
            notePanel.post(() -> {
                float[] d = offsetToNoteCard();
                notePanel.setTranslationX(d[0]);
                notePanel.setTranslationY(d[1]);
                notePanel.setScaleX(0.25f);
                notePanel.setScaleY(0.25f);
                notePanel.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(220).start();
            });
        } else {
            // Another key: a little pulse so repeated notes still register.
            popName.setScaleX(1.08f);
            popName.setScaleY(1.08f);
            popName.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        }
    }

    private void hideNotePopup() {
        if (!popupShown) return;
        popupShown = false;
        float[] d = offsetToNoteCard();
        notePanel.animate().cancel();
        notePanel.animate().translationX(d[0]).translationY(d[1]).scaleX(0.2f).scaleY(0.2f)
                .setDuration(300).start();
        notePopup.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> {
                    if (!popupShown) notePopup.setVisibility(View.GONE);
                }).start();
    }

    /** A full-width button showing the chosen device; text wraps at any font size. */
    private Button pickerButton(View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(19);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setSingleLine(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(rounded(Color.rgb(28, 28, 30), BORDER, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 15, MUTED);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, BORDER, 16));
        c.setPadding(dp(20), dp(6), dp(20), dp(18));
        return c;
    }

    /**
     * TV (Fire TV remote): the custom button backgrounds hide Android's focus
     * highlight, so ring whichever control the D-pad is on. Drawn as the view's
     * foreground, which the background swaps in updateToggle()/updatePanic() don't touch.
     */
    private void showRemoteFocus() {
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (oldFocus != null) oldFocus.setForeground(null);
            if (newFocus != null) {
                GradientDrawable ring = rounded(Color.TRANSPARENT, 0, 14);
                ring.setStroke(dp(3), ACCENT);
                newFocus.setForeground(ring);
            }
        });
        toggle.requestFocus();
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateToggle() {
        boolean on = bridge.isOn();
        toggle.setText(on ? "●  ON  —  tap to stop" : "OFF  —  tap to start");
        toggle.setBackground(rounded(on ? ON_GREEN : OFF_GREY, 0, 16));
        toggle.setTextColor(Color.WHITE);
    }

    /** "All notes off" is only live while something is sounding; dimmed otherwise. */
    private void updatePanic() {
        boolean sounding = bridge.isSounding();
        if (panic.isEnabled() == sounding && panic.getTag() != null) return;
        panic.setTag(Boolean.TRUE);
        panic.setEnabled(sounding);
        panic.setTextColor(sounding ? TEXT : Color.rgb(110, 110, 110));
        panic.setBackground(rounded(CARD, sounding ? BORDER : Color.rgb(50, 50, 50), 14));
    }

    private final Runnable ticker = new Runnable() {
        private long shownCount = -1;
        private long shownNoteOns = -1;
        private boolean chordArmed;
        private int[] settlingKeys = new int[0];
        private long settlingSince;

        @Override
        public void run() {
            long count = bridge.msgCount();
            long ons = bridge.noteOnCount();
            if (ons != shownNoteOns) {
                boolean first = shownNoteOns < 0;  // don't pop up for a note played while we were away
                shownNoteOns = ons;
                chordText = null;
                shownCount = -1;  // back to the single note in the NOTE card
                if (!first && bridge.lastNoteNum() >= 0) {
                    lastNoteAt = System.currentTimeMillis();
                    showNote(bridge.lastNoteNum());
                    chordArmed = true;
                }
            }
            // After a key goes down, name the chord once the keys have stayed put
            // for CHORD_SETTLE_MS — so a quick legato overlap never counts.
            // Letting keys go never re-arms it, so releasing doesn't rename the chord.
            if (chordArmed) {
                int[] keys = bridge.keysDown();
                long now = System.currentTimeMillis();
                if (!java.util.Arrays.equals(keys, settlingKeys)) {
                    settlingKeys = keys;
                    settlingSince = now;
                } else if (now - settlingSince >= CHORD_SETTLE_MS + (Chord.isPair(keys) ? Chord.PAIR_EXTRA_MS : 0)) {
                    chordArmed = false;
                    String name = Chord.name(keys);
                    if (name != null) {
                        showChord(keys, name);
                        String interval = Chord.interval(keys);
                        chordText = name + "\n" + noteList(keys, "  ") + (interval != null ? "  \u00B7  " + interval : "");
                        shownCount = -1;
                    }
                }
            }
            if (count != shownCount) {
                shownCount = count;
                lastMsg.setText(count > 0 ? bridge.lastText() + "\n" + count + " messages forwarded" : "—");
                bigNote.setText(chordText != null ? chordText : bridge.lastNote());
            }
            // Music stopped (no keys, no pedal) for a moment: fold back into the NOTE card.
            if (popupShown && !bridge.isSounding()
                    && System.currentTimeMillis() - lastNoteAt > POPUP_LINGER_MS) {
                hideNotePopup();
            }
            updatePanic();
            ui.postDelayed(this, 50);
        }
    };
}
