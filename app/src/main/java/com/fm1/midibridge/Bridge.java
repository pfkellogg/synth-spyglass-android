package com.fm1.midibridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MIDI forwarding itself: one device's output (e.g. an M-Audio
 * Keystation) to another device's input (e.g. an Arturia interface whose
 * MIDI OUT drives the FM-1). One instance per app process, owned by nobody
 * in particular — BridgeService keeps the process alive while it's ON, and
 * MainActivity is just a remote control for it. All methods run on the main
 * thread except the Forwarder, which runs on a MIDI binder thread.
 *
 * The chosen pair and the on/off state are remembered; while ON, it
 * (re)connects by itself whenever both devices are plugged in.
 */
public final class Bridge {

    /**
     * One port on one device: a "from" (device output) or a "to" (device input).
     * Listed by Android's MIDI service (`info`), or, on a device without one (an
     * Onn Google TV), found over USB (`usb` only, `info` null).
     */
    public static final class PortRef {
        final MidiDeviceInfo info;
        final UsbDevice usb;
        final int port;
        final String label;

        PortRef(MidiDeviceInfo info, int port, String label) {
            this.info = info;
            this.usb = usbDevice(info);
            this.port = port;
            this.label = label;
        }

        PortRef(UsbDevice usb, int port, String label) {
            this.info = null;
            this.usb = usb;
            this.port = port;
            this.label = label;
        }

        /** True when both ports are on the same physical device. */
        boolean sameDevice(PortRef o) {
            if (info != null && o.info != null) return info.getId() == o.info.getId();
            return usb != null && o.usb != null && usb.getDeviceName().equals(o.usb.getDeviceName());
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // Last: a computer's keyboard over Wi-Fi (WifiMidiService), when none is plugged in here.
    private static final String[] FROM_HINTS = {"keystation", "m-audio", "(wi-fi)"};
    // MIDI interfaces first, then the FM-1 itself on USB. Its USB chip is made by Jieli, and
    // Android names it "<manufacturer> <product>" = "Jieli Technology USB Composite Device".
    private static final String[] TO_HINTS = {"arturia", "minifuse", "audiofuse", "fm-1", "fm1", "m-vave", "cuvave", "jieli", "composite"};
    // Devices that only receive: the FM-1 sends no MIDI over USB, even when its knobs are turned.
    // "USB Composite Device" alone is a stock chip name, so only count it with Jieli's name too.
    private static final String[] SILENT_HINTS = {"fm-1", "fm1"};
    public static final String SILENT_FROM = "The FM-1 is all ears, no mouth — it plays the MIDI it gets, "
            + "but never sends any. Choose your keyboard as FROM.";

    private static Bridge instance;

    public static Bridge get(Context ctx) {
        if (instance == null) instance = new Bridge(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final MidiManager midi;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();

    private final List<PortRef> sources = new ArrayList<>();
    private final List<PortRef> dests = new ArrayList<>();

    private boolean on;
    private boolean connecting;
    private MidiDevice fromDev, toDev;
    private java.io.Closeable fromPort;      // MidiOutputPort, or the UsbMidi it reads from
    private volatile MidiReceiver toPort;    // MidiInputPort, or a UsbMidi sender
    private UsbMidi usbFrom, usbTo;          // direct-USB mode only (see directUsb())
    private final UsbManager usb;
    private String askedPermissionFor;       // device we're waiting on the Allow dialog for
    private String connectedFrom, connectedTo;
    // FROM is the computer over Wi-Fi and nothing is plugged in here to play: just show the notes.
    private volatile boolean notesOnly;
    private String error;

    private volatile long msgCount = 0;
    private volatile String lastText = "";
    private volatile String lastNote = "—";
    private volatile int lastNoteNum = -1;
    private volatile long noteOnCount = 0;
    // How hard the last key was hit, and whether the keyboard sends more than one velocity
    // (some send every note at the same one: then there's nothing worth showing).
    private volatile int lastVelocity = -1, firstVelocity = -1;
    private volatile boolean velocityVaries;

    // What's sounding on the TO device: held notes per channel, and sustain.
    // Fed from both the keyboard and other apps, so it covers everything we send.
    private final int[][] held = new int[16][128];
    private final boolean[] sustain = new boolean[16];
    private int heldCount;
    // Keys down on the keyboard alone (not other apps' notes), for chord names.
    private final int[] keysDown = new int[128];
    private final Tracker keysTracker = new Tracker(true), appTracker = new Tracker(false);

    private Bridge(Context ctx) {
        this.ctx = ctx;
        midi = ctx.getSystemService(MidiManager.class);
        prefs = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        on = prefs.getBoolean("on", false);
        usb = ctx.getSystemService(UsbManager.class);
        if (directUsb()) {
            IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerUsbEvents(f);
        }
        if (midi != null) {
            midi.registerDeviceCallback(deviceCallback, ui);
        }
        if (midi != null || directUsb()) {
            refreshLists();
            tryConnect();  // e.g. Android restarted BridgeService after killing the app
        }
    }

    // ------------------------------------------------------------------
    // Public API (main thread)
    // ------------------------------------------------------------------

    public boolean hasMidi() {
        return midi != null || directUsb();
    }

    public boolean isOn() {
        return on;
    }

    /** Switches the bridge on/off, and starts/stops the foreground service that keeps it running in the background. */
    public void setOn(boolean want) {
        on = want;
        prefs.edit().putBoolean("on", want).apply();
        Intent svc = new Intent(ctx, BridgeService.class);
        if (want) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);  // Fire TV (Android 7.1)
            tryConnect();
        } else {
            disconnect(true);
            ctx.stopService(svc);
        }
        changed();
    }

    public List<PortRef> sources() {
        return sources;
    }

    /** Places to send to — minus the FROM device's own ports (the keyboard can't play itself). */
    public List<PortRef> dests() {
        PortRef from = from();
        if (from == null) return dests;
        List<PortRef> out = new ArrayList<>();
        for (PortRef r : dests) if (!r.sameDevice(from)) out.add(r);
        return out;
    }

    public PortRef from() {
        return pick(sources, prefs.getString("from", null), FROM_HINTS, null);
    }

    public PortRef to() {
        return pick(dests, prefs.getString("to", null), TO_HINTS, from());
    }

    public void choose(PortRef from, PortRef to) {
        String oldFrom = from() != null ? from().label : null;
        String oldTo = to() != null ? to().label : null;
        SharedPreferences.Editor e = prefs.edit();
        if (from != null) e.putString("from", from.label);
        if (to != null) e.putString("to", to.label);
        e.apply();
        boolean same = from != null && from.label.equals(oldFrom) && to != null && to.label.equals(oldTo);
        if (on && !same) {
            disconnect(false);
            tryConnect();
        }
        changed();
    }

    /** FROM is a device that never sends MIDI (the FM-1), so there's nothing to forward. */
    public boolean fromIsSilent() {
        PortRef from = from();
        if (from == null) return false;
        String l = from.label.toLowerCase(Locale.US);
        for (String h : SILENT_HINTS) if (l.contains(h)) return true;
        return l.contains("jieli") && l.contains("composite");
    }

    public String status() {
        if (!hasMidi()) return "This device has no MIDI support.";
        if (fromIsSilent()) return SILENT_FROM;
        if (toPort != null && fromPort != null) return "Forwarding\n" + connectedFrom + "\n→ " + connectedTo;
        if (notesOnly && fromPort != null) return "Showing the notes from\n" + connectedFrom
                + "\n(plug the FM-1 in here to play it too)";
        if (error != null) return error;
        if (sources.isEmpty() && dests.isEmpty()) return "No MIDI devices found — check the hub.";
        if (to() == null) return "Only the keyboard is plugged in.\nThe tablet doesn't see the FM-1 — check its USB cable, "
                + "that it's switched on, and the hub's power.";
        if (!on) return "Stopped.";
        if (connecting) return "Connecting…";
        return "Waiting for both devices to be plugged in…";
    }

    /** One line for the notification. */
    public String shortStatus() {
        if (toPort != null && fromPort != null) return connectedFrom + " → " + connectedTo;
        if (notesOnly && fromPort != null) return "Notes from " + connectedFrom;
        if (error != null) return error;
        return connecting ? "Connecting…" : "Waiting for both devices…";
    }

    public long msgCount() {
        return msgCount;
    }

    public String lastText() {
        return lastText;
    }

    public String lastNote() {
        return lastNote;
    }

    /** MIDI number of the last key played on the keyboard, or -1 if none yet. */
    public int lastNoteNum() {
        return lastNoteNum;
    }

    /** How hard the last key was hit (1-127), or -1 when the keyboard doesn't vary it. */
    public int lastVelocity() {
        return velocityVaries ? lastVelocity : -1;
    }

    /** Goes up by one for every key pressed on the keyboard. */
    public long noteOnCount() {
        return noteOnCount;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /**
     * MIDI from another app (via BridgeMidiService), mixed into the stream to
     * the TO device. Dropped while the bridge isn't connected.
     */
    public void sendFromApp(byte[] msg, int offset, int count) throws IOException {
        MidiReceiver out = toPort;
        if (out == null) return;
        out.send(msg, offset, count);
        appTracker.feed(msg, offset, count);
    }

    /** True when sendFromApp() reaches a device (the TO port is open). */
    public boolean canSendFromApp() {
        return toPort != null;
    }

    /** True while a note or the sustain pedal is down on the TO device (or a note got stuck). */
    public synchronized boolean isSounding() {
        if (heldCount > 0) return true;
        for (boolean s : sustain) if (s) return true;
        return false;
    }

    /** Keys held down on the keyboard right now (sustain pedal not counted), lowest first. */
    public synchronized int[] keysDown() {
        int count = 0;
        int[] notes = new int[128];
        for (int n = 0; n < 128; n++) if (keysDown[n] > 0) notes[count++] = n;
        return java.util.Arrays.copyOf(notes, count);
    }

    private synchronized void clearSounding() {
        for (int[] ch : held) java.util.Arrays.fill(ch, 0);
        java.util.Arrays.fill(sustain, false);
        java.util.Arrays.fill(keysDown, 0);
        heldCount = 0;
    }

    private synchronized void track(int status, int d1, int d2, boolean fromKeys) {
        int ch = status & 0x0F;
        switch (status & 0xF0) {
            case 0x90:
                if (d2 > 0) {
                    held[ch][d1]++;
                    heldCount++;
                    if (fromKeys) keysDown[d1]++;
                    break;
                }
                // velocity 0 = note off
            case 0x80:
                if (held[ch][d1] > 0) {
                    held[ch][d1]--;
                    heldCount--;
                }
                if (fromKeys && keysDown[d1] > 0) keysDown[d1]--;
                break;
            case 0xB0:
                if (d1 == 64) sustain[ch] = d2 >= 64;
                else if (d1 == 120 || d1 == 123) {  // all sound off / all notes off
                    for (int n = 0; n < 128; n++) heldCount -= held[ch][n];
                    java.util.Arrays.fill(held[ch], 0);
                    if (fromKeys) java.util.Arrays.fill(keysDown, 0);
                    if (d1 == 120) sustain[ch] = false;
                }
                break;
        }
    }

    /** Splits one source's byte stream (running status allowed) into messages for track(). */
    private class Tracker {
        private final boolean fromKeys;
        private int status, d1, have;

        Tracker(boolean fromKeys) {
            this.fromKeys = fromKeys;
        }

        void feed(byte[] msg, int offset, int count) {
            for (int i = offset; i < offset + count; i++) {
                int b = msg[i] & 0xFF;
                if (b >= 0xF8) continue;              // real-time
                if (b >= 0x80) {                      // new status (SysEx etc. ignored)
                    status = b < 0xF0 ? b : 0;
                    have = 0;
                    continue;
                }
                if (status == 0) continue;
                int type = status & 0xF0;
                if (type == 0xC0 || type == 0xD0) continue;  // one data byte, nothing to track
                if (have == 0) {
                    d1 = b;
                    have = 1;
                } else {
                    track(status, d1, b, fromKeys);
                    have = 0;                         // running status: next pair reuses it
                }
            }
        }
    }

    private boolean isOwnDevice(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        return info.getType() == MidiDeviceInfo.TYPE_VIRTUAL
                && "Synth Spyglass".equals(p.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
                && "Synth Spyglass".equals(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER));
    }

    /**
     * The MIDI Bridge app's own devices, when it's installed too: its "Mac (Wi-Fi)" would fight ours
     * for the same network port, and its "MIDI Bridge" input leads into that other app.
     */
    private static boolean isMidiBridgeApp(MidiDeviceInfo info) {
        return info.getType() == MidiDeviceInfo.TYPE_VIRTUAL
                && "Android USB MIDI Bridge".equals(info.getProperties().getString(MidiDeviceInfo.PROPERTY_MANUFACTURER));
    }

    /** Sustain off + all notes off on every channel, so nothing's left hanging. */
    public void allNotesOff() {
        MidiReceiver out = toPort;
        if (out == null) {
            clearSounding();  // notes-only: nothing sounding anywhere but the screen
            return;
        }
        byte[] b = new byte[6];
        try {
            for (int ch = 0; ch < 16; ch++) {
                b[0] = (byte) (0xB0 | ch); b[1] = 64; b[2] = 0;
                b[3] = (byte) (0xB0 | ch); b[4] = 123; b[5] = 0;
                out.send(b, 0, b.length);
            }
        } catch (IOException ignored) {
        }
        clearSounding();
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    // ------------------------------------------------------------------
    // Forwarding (runs on a MIDI binder thread)
    // ------------------------------------------------------------------

    private class Forwarder extends MidiReceiver {
        private final byte[] buf = new byte[1024];

        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
            MidiReceiver out = toPort;
            if (out == null && !notesOnly) return;
            // Drop real-time bytes (clock, active sensing, etc.) — they can
            // appear anywhere in the stream and the FM-1 has no use for them.
            // Channel messages are moved to channel 1: the FM-1 only listens
            // there, and a keyboard knocked onto another channel goes silent.
            int n = 0;
            for (int i = offset; i < offset + count && n < buf.length; i++) {
                int b = msg[i] & 0xFF;
                if (b >= 0xF8) continue;
                buf[n++] = (b >= 0x80 && b < 0xF0) ? (byte) (b & 0xF0) : msg[i];
            }
            if (n == 0) return;
            if (out != null) out.send(buf, 0, n);
            keysTracker.feed(buf, 0, n);
            msgCount++;
            lastText = describe(buf, n);
            if ((buf[0] & 0xF0) == 0x90 && n > 2 && buf[2] != 0) {
                int note = buf[1] & 0x7F;
                lastNote = String.format(Locale.US, "%s   %.1f Hz", noteName(note), noteHz(note));
                lastNoteNum = note;
                int vel = buf[2] & 0x7F;
                if (firstVelocity < 0) firstVelocity = vel;
                else if (vel != firstVelocity) velocityVaries = true;
                lastVelocity = vel;
                noteOnCount++;
            }
        }
    }

    static String describe(byte[] b, int n) {
        int s = b[0] & 0xFF;
        int ch = (s & 0x0F) + 1;
        int d1 = n > 1 ? b[1] & 0x7F : 0;
        int d2 = n > 2 ? b[2] & 0x7F : 0;
        switch (s & 0xF0) {
            case 0x90:
                if (d2 > 0) return String.format(Locale.US, "Note on   %s  %.1f Hz  vel %d  (ch %d)", noteName(d1), noteHz(d1), d2, ch);
                // velocity 0 = note off
            case 0x80:
                return String.format(Locale.US, "Note off  %s  %.1f Hz  (ch %d)", noteName(d1), noteHz(d1), ch);
            case 0xB0:
                if (d1 == 64) return "Sustain " + (d2 >= 64 ? "ON" : "off");
                if (d1 == 1) return "Mod wheel " + d2;
                return String.format(Locale.US, "CC %d = %d  (ch %d)", d1, d2, ch);
            case 0xC0:
                return String.format(Locale.US, "Program change %d", d1 + 1);
            case 0xE0:
                return "Pitch bend " + (((d2 << 7) | d1) - 8192);
            case 0xD0:
                return "Aftertouch " + d1;
            default:
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(n, 8); i++) sb.append(String.format(Locale.US, "%02X ", b[i] & 0xFF));
                return sb.toString().trim();
        }
    }

    /** Equal-tempered pitch, A4 (note 69) = 440 Hz. */
    public static double noteHz(int note) {
        return NoteNames.hz(note);
    }

    public static String pitchClass(int note) {
        return NoteNames.pitchClass(note);
    }

    public static String noteName(int note) {
        return NoteNames.name(note);
    }

    // ------------------------------------------------------------------
    // Device lists
    // ------------------------------------------------------------------

    private final MidiManager.DeviceCallback deviceCallback = new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo device) {
            refreshLists();
            if (notesOnly && to() != null) disconnect(false);  // something to play on now: connect to it
            tryConnect();
            changed();
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo device) {
            refreshLists();
            if (fromDev != null && device.getId() == fromDev.getInfo().getId()
                    || toDev != null && device.getId() == toDev.getInfo().getId()) {
                disconnect(false);
            }
            changed();
        }
    };

    private static String deviceName(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null) name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        return name != null ? name : "MIDI device " + info.getId();
    }

    private static String portLabel(MidiDeviceInfo info, int type, int index, int portCount) {
        String name = deviceName(info);
        if (portCount <= 1) return name;
        String portName = null;
        for (MidiDeviceInfo.PortInfo pi : info.getPorts()) {
            if (pi.getType() == type && pi.getPortNumber() == index) portName = pi.getName();
        }
        return name + " — port " + (index + 1)
                + (portName != null && !portName.isEmpty() ? " (" + portName + ")" : "");
    }

    @SuppressWarnings("deprecation")  // getDevices() still lists USB devices on every version
    private void refreshLists() {
        sources.clear();
        dests.clear();
        if (midi == null) {
            refreshUsbLists();
            return;
        }
        for (MidiDeviceInfo info : midi.getDevices()) {
            if (isOwnDevice(info)) continue;   // our own "Synth Spyglass" input for other apps
            if (isMidiBridgeApp(info)) continue;
            int outs = info.getOutputPortCount();
            for (int i = 0; i < outs; i++) {
                sources.add(new PortRef(info, i, portLabel(info, MidiDeviceInfo.PortInfo.TYPE_OUTPUT, i, outs)));
            }
            int ins = info.getInputPortCount();
            for (int i = 0; i < ins; i++) {
                dests.add(new PortRef(info, i, portLabel(info, MidiDeviceInfo.PortInfo.TYPE_INPUT, i, ins)));
            }
        }
    }

    /** The saved choice if it's plugged in (and not on `avoid`'s device), else the first port whose name matches a hint, else any other device. */
    private static PortRef pick(List<PortRef> list, String saved, String[] hints, PortRef avoid) {
        if (saved != null) {
            // Never reuse a saved TO that's on the FROM device (e.g. the keyboard's own input port).
            for (PortRef r : list)
                if (r.label.equals(saved) && (avoid == null || !r.sameDevice(avoid))) return r;
        }
        for (String h : hints) {
            for (PortRef r : list) {
                if (avoid != null && r.sameDevice(avoid)) continue;
                if (r.label.toLowerCase(Locale.US).contains(h)) return r;
            }
        }
        for (PortRef r : list) {
            if (avoid == null || !r.sameDevice(avoid)) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    private void tryConnect() {
        if (!on || connecting || fromPort != null || !hasMidi()) return;
        PortRef from = from();
        PortRef to = to();
        error = null;
        if (from == null) return;
        if (to == null) {
            if (isWifi(from)) connectNotesOnly(from);
            return;
        }
        if (fromIsSilent()) return;
        if (from.sameDevice(to)) {
            error = "Pick different devices for From and To.";
            return;
        }
        connecting = true;
        if (directUsb() && connectUsb(from, to)) return;
        midi.openDevice(from.info, fDev -> {
            if (fDev == null) {
                failConnect("Couldn't open " + from.label);
                return;
            }
            fromDev = fDev;
            midi.openDevice(to.info, tDev -> {
                if (tDev == null) {
                    failConnect("Couldn't open " + to.label);
                    return;
                }
                finishConnect(from, to, tDev);
            }, ui);
        }, ui);
    }

    /** FROM is a computer's keyboard over Wi-Fi (WifiMidiService). */
    public boolean fromIsWifi() {
        PortRef from = from();
        return from != null && isWifi(from);
    }

    private static boolean isWifi(PortRef r) {
        return r.info != null && r.info.getType() == MidiDeviceInfo.TYPE_VIRTUAL
                && "Mac (Wi-Fi)".equals(r.info.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT));
    }

    /** Opens just the computer over Wi-Fi, so its notes show here with nothing to play them on. */
    private void connectNotesOnly(PortRef from) {
        connecting = true;
        midi.openDevice(from.info, fDev -> {
            if (fDev == null) {
                failConnect("Couldn't open " + from.label);
                return;
            }
            fromDev = fDev;
            portOpener().post(() -> {
                MidiOutputPort out = fDev.openOutputPort(from.port);
                ui.post(() -> {
                    connecting = false;
                    if (fDev != fromDev || out == null || !on) {
                        closeQuietly(out);
                        if (fDev == fromDev) disconnect(false);
                        tryConnect();
                        changed();
                        return;
                    }
                    fromPort = out;
                    notesOnly = true;
                    connectedFrom = from.label;
                    out.connect(new Forwarder());
                    changed();
                });
            });
        }, ui);
    }

    /** Sends bytes down the keyboard's path, as if played on it. For the debug-only TestPlayer (src/debug). */
    void playAsKeyboard(byte[] msg) throws IOException {
        new Forwarder().onSend(msg, 0, msg.length, 0);
    }

    /**
     * Opening a port is a blocking call into the MIDI service, and on Fire OS it
     * can hang (a USB port left stuck by an app that was killed while
     * connected). Open them off the main thread so the screen still comes up,
     * showing "Connecting…", instead of an ANR and a black screen.
     */
    private Handler portOpener;

    private Handler portOpener() {
        if (portOpener == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MidiBridge-ports");
            t.start();
            portOpener = new Handler(t.getLooper());
        }
        return portOpener;
    }

    private void finishConnect(PortRef from, PortRef to, MidiDevice tDev) {
        toDev = tDev;
        MidiDevice fDev = fromDev;
        portOpener().post(() -> {
            MidiInputPort in = tDev.openInputPort(to.port);
            MidiOutputPort out = fDev.openOutputPort(from.port);
            ui.post(() -> portsOpened(from, to, fDev, in, out));
        });
    }

    private void portsOpened(PortRef from, PortRef to, MidiDevice fDev, MidiInputPort in, MidiOutputPort out) {
        if (fDev != fromDev) {  // disconnected while we were opening
            closeQuietly(in);
            closeQuietly(out);
            connecting = false;
            tryConnect();
            changed();
            return;
        }
        fromPort = out;
        toPort = in;
        connecting = false;
        if (in == null || out == null) {
            String busy = in == null ? to.label : from.label;
            disconnect(false);
            error = "Couldn't open " + busy + " (another app may be using it).";
            changed();
            return;
        }
        connectedFrom = from.label;
        connectedTo = to.label;
        out.connect(new Forwarder());
        if (!on) disconnect(true);  // switched off while we were opening
        changed();
    }

    // ------------------------------------------------------------------
    // Direct USB (Fire TV)
    // ------------------------------------------------------------------

    private static final String ACTION_USB_PERMISSION = "com.fm1.midibridge.USB_PERMISSION";

    /**
     * Drive USB MIDI devices straight over USB (see UsbMidi) instead of through
     * Android's MIDI service:
     * - Fire OS (Android 7.1): its MIDI service jams after a restart with both
     *   devices plugged in. (The phone/tablet builds need Android 10+.)
     * - Devices with no MIDI service at all, like the Onn Full HD Google TV
     *   stick (Android 14): the devices are found over USB too (refreshUsbLists).
     */
    private boolean directUsb() {
        return usb != null && (Build.VERSION.SDK_INT < 29 || midi == null);
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")  // flags only exist from Android 13
    private void registerUsbEvents(IntentFilter f) {
        // Exported: the USB permission answer comes back through our PendingIntent from the system.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(usbEvents, f, null, ui, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(usbEvents, f, null, ui);
    }

    private static UsbDevice usbDevice(MidiDeviceInfo info) {
        return info.getProperties().getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE);
    }

    /**
     * No MIDI service: list the USB MIDI devices plugged in, one port each (the
     * device's first, "cable 0", which is the keyboard's main port). Named like
     * Android's MIDI service names them, "<manufacturer> <product>", so the FROM /
     * TO hints and saved choices still match.
     */
    private void refreshUsbLists() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbMidi.midiInterface(d) == null) continue;
            String name = ((d.getManufacturerName() != null ? d.getManufacturerName() + " " : "")
                    + (d.getProductName() != null ? d.getProductName() : "USB MIDI device")).trim();
            if (UsbMidi.hasInput(d)) sources.add(new PortRef(d, 0, name));
            if (UsbMidi.hasOutput(d)) dests.add(new PortRef(d, 0, name));
        }
    }

    /** Connects over USB if both ends are USB devices. False = not both USB: use the MIDI service. */
    private boolean connectUsb(PortRef from, PortRef to) {
        UsbDevice fd = from.usb, td = to.usb;
        if (fd == null || td == null) return false;
        for (UsbDevice d : new UsbDevice[]{fd, td}) {
            if (usb.hasPermission(d)) continue;
            connecting = false;
            error = "On the TV, allow Synth Spyglass to use " + d.getProductName()
                    + ". Choose \"Allow Always\" so it isn't asked again after a restart.";
            if (!d.getDeviceName().equals(askedPermissionFor)) {  // one dialog at a time
                askedPermissionFor = d.getDeviceName();
                // Mutable: Android adds the device and the answer to it.
                usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 0,
                        new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName()),
                        Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            }
            changed();
            return true;
        }
        portOpener().post(() -> {
            UsbMidi f = null, t = null;
            String err = null;
            try {
                f = UsbMidi.open(usb, fd);
                t = UsbMidi.open(usb, td);
            } catch (IOException e) {
                err = e.getMessage();
                closeQuietly(f);
                f = null;
            }
            UsbMidi ff = f, tt = t;
            String ee = err;
            ui.post(() -> usbOpened(from, to, ff, tt, ee));
        });
        return true;
    }

    private void usbOpened(PortRef from, PortRef to, UsbMidi f, UsbMidi t, String err) {
        connecting = false;
        if (err == null && on) {
            try {
                f.startReading(from.port, new Forwarder());
            } catch (IOException e) {
                err = e.getMessage();
            }
        }
        if (err != null || !on) {
            closeQuietly(f);
            closeQuietly(t);
            error = err;
            changed();
            return;
        }
        usbFrom = f;
        usbTo = t;
        fromPort = f;
        toPort = t.sender(to.port);
        connectedFrom = from.label;
        connectedTo = to.label;
        error = null;
        changed();
    }

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_USB_PERMISSION.equals(i.getAction())) {
                askedPermissionFor = null;
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                // A MIDI device went away while bridging over USB: drop the link, reconnect if both are still here.
                if (usbFrom != null && d != null && UsbMidi.midiInterface(d) != null) disconnect(false);
            }
            refreshLists();
            tryConnect();
            changed();
        }
    };

    private void failConnect(String why) {
        connecting = false;
        disconnect(false);
        error = why;
        changed();
    }

    private void disconnect(boolean releaseNotes) {
        if (releaseNotes) allNotesOff();
        clearSounding();
        MidiReceiver in = toPort;
        toPort = null;
        closeQuietly(fromPort);
        if (in instanceof java.io.Closeable) closeQuietly((java.io.Closeable) in);
        closeQuietly(usbTo);
        usbFrom = null;
        usbTo = null;
        if (toDev != null && toDev != fromDev) closeQuietly(toDev);
        closeQuietly(fromDev);
        fromPort = null;
        fromDev = null;
        toDev = null;
        notesOnly = false;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
