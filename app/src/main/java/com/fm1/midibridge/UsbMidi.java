package com.fm1.midibridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiReceiver;

import java.io.Closeable;
import java.io.IOException;

/**
 * A class-compliant USB MIDI device driven straight over USB (Android's USB
 * host API), bypassing Android's MIDI service and the kernel's ALSA driver.
 *
 * Used on Fire TV: when the stick boots with the keyboard and the FM-1 already
 * plugged in, Fire OS registers both on the same ALSA sound card, and opening
 * the keyboard's MIDI port then hangs until it's replugged. Talking USB MIDI
 * directly never touches ALSA, so a restart can't jam it.
 *
 * USB MIDI moves 4-byte event packets: byte 0 = cable number (high nibble, the
 * device's "port") and Code Index Number (low nibble, says how many of the
 * next 3 bytes are MIDI), then the MIDI bytes.
 */
final class UsbMidi implements Closeable {

    private static final int AUDIO_CLASS = 1, MIDI_STREAMING = 3;
    // MIDI bytes carried by each Code Index Number (0 and 1 are reserved).
    private static final int[] CIN_LENGTH = {0, 0, 2, 3, 3, 1, 2, 3, 3, 3, 3, 3, 2, 2, 3, 1};

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in, out;
    private volatile boolean closed;
    private Thread reader;

    /** The device's MIDI-streaming interface, or null if it isn't a USB MIDI device. */
    static UsbInterface midiInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == AUDIO_CLASS && f.getInterfaceSubclass() == MIDI_STREAMING) return f;
        }
        return null;
    }

    /** The device sends MIDI (its MIDI interface has an IN endpoint): a possible FROM. */
    static boolean hasInput(UsbDevice d) {
        return endpoint(d, UsbConstants.USB_DIR_IN);
    }

    /** The device takes MIDI (its MIDI interface has an OUT endpoint): a possible TO. */
    static boolean hasOutput(UsbDevice d) {
        return endpoint(d, UsbConstants.USB_DIR_OUT);
    }

    private static boolean endpoint(UsbDevice d, int dir) {
        UsbInterface f = midiInterface(d);
        if (f == null) return false;
        for (int i = 0; i < f.getEndpointCount(); i++) if (f.getEndpoint(i).getDirection() == dir) return true;
        return false;
    }

    /** Opens and claims the device's MIDI interface (taking it from the kernel driver). Blocking: call off the main thread. */
    static UsbMidi open(UsbManager um, UsbDevice d) throws IOException {
        UsbInterface f = midiInterface(d);
        if (f == null) throw new IOException(d.getProductName() + " isn't a USB MIDI device");
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < f.getEndpointCount(); i++) {
            UsbEndpoint e = f.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK && e.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) {
                if (in == null) in = e;
            } else if (out == null) {
                out = e;
            }
        }
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("Couldn't open " + d.getProductName() + " over USB");
        if (!c.claimInterface(f, true)) {
            c.close();
            throw new IOException("Couldn't claim " + d.getProductName() + "'s MIDI interface");
        }
        return new UsbMidi(c, f, in, out);
    }

    private UsbMidi(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.intf = intf;
        this.in = in;
        this.out = out;
    }

    /** Reads the device's MIDI on a background thread and hands `cable`'s messages to `r`. */
    void startReading(int cable, MidiReceiver r) throws IOException {
        if (in == null) throw new IOException("Device has no MIDI output");
        reader = new Thread(() -> {
            byte[] packet = new byte[in.getMaxPacketSize() > 0 ? in.getMaxPacketSize() : 64];
            byte[] midi = new byte[packet.length];
            while (!closed) {
                int n = conn.bulkTransfer(in, packet, packet.length, 250);
                if (n <= 0) continue;  // timeout (no keys played) or a hiccup
                int m = 0;
                for (int i = 0; i + 4 <= n; i += 4) {
                    if (((packet[i] >> 4) & 0x0F) != cable) continue;
                    int len = CIN_LENGTH[packet[i] & 0x0F];
                    for (int k = 1; k <= len; k++) midi[m++] = packet[i + k];
                }
                if (m == 0) continue;
                try {
                    r.send(midi, 0, m);
                } catch (IOException ignored) {
                }
            }
        }, "MidiBridge-usb-in");
        reader.start();
    }

    /** A receiver that sends MIDI to this device's `cable`. */
    MidiReceiver sender(int cable) {
        return new Sender(cable);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (reader != null) {
            try {
                reader.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        conn.releaseInterface(intf);
        conn.close();
    }

    /** Turns a MIDI byte stream (running status and SysEx allowed) into USB MIDI packets. */
    private final class Sender extends MidiReceiver {
        private final int cable;
        private int status, need;          // running status, and data bytes it takes
        private final byte[] msg = new byte[3];
        private int have;
        private final byte[] sysex = new byte[3];
        private int sysexLen;
        private boolean inSysex;

        Sender(int cable) {
            this.cable = cable << 4;
        }

        @Override
        public synchronized void onSend(byte[] data, int offset, int count, long timestamp) throws IOException {
            if (closed || out == null) return;
            byte[] buf = new byte[count * 4 + 4];
            int n = 0;
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0xF8) {                                 // real-time: one byte, anywhere
                    n = put(buf, n, 0x0F, b, 0, 0);
                    continue;
                }
                if (b == 0xF0) {
                    inSysex = true;
                    sysexLen = 0;
                }
                if (inSysex) {
                    sysex[sysexLen++] = (byte) b;
                    if (b == 0xF7) {                             // end: 1-3 bytes left, CIN 5/6/7
                        n = put(buf, n, 4 + sysexLen, sysex[0] & 0xFF,
                                sysexLen > 1 ? sysex[1] & 0xFF : 0, sysexLen > 2 ? sysex[2] & 0xFF : 0);
                        inSysex = false;
                        sysexLen = 0;
                    } else if (sysexLen == 3) {                  // start/continue: CIN 4
                        n = put(buf, n, 0x04, sysex[0] & 0xFF, sysex[1] & 0xFF, sysex[2] & 0xFF);
                        sysexLen = 0;
                    }
                    continue;
                }
                if (b >= 0x80) {                                 // new status
                    status = b;
                    have = 0;
                    need = dataBytes(b);
                    if (need == 0) {                             // tune request etc.
                        n = put(buf, n, 0x05, b, 0, 0);
                        status = 0;
                    }
                    continue;
                }
                if (status == 0) continue;                       // stray data byte
                msg[have++] = (byte) b;
                if (have == need) {
                    int cin = status < 0xF0 ? status >> 4 : (need == 1 ? 0x02 : 0x03);
                    n = put(buf, n, cin, status, msg[0] & 0xFF, need > 1 ? msg[1] & 0xFF : 0);
                    have = 0;
                    if (status >= 0xF0) status = 0;              // no running status for system common
                }
            }
            if (n > 0 && conn.bulkTransfer(out, buf, n, 1000) < 0) throw new IOException("USB send failed");
        }

        private int put(byte[] buf, int n, int cin, int b1, int b2, int b3) {
            buf[n] = (byte) (cable | (cin & 0x0F));
            buf[n + 1] = (byte) b1;
            buf[n + 2] = (byte) b2;
            buf[n + 3] = (byte) b3;
            return n + 4;
        }

        private int dataBytes(int status) {
            switch (status & 0xF0) {
                case 0xC0:
                case 0xD0:
                    return 1;
                case 0xF0:
                    return status == 0xF1 || status == 0xF3 ? 1 : status == 0xF2 ? 2 : 0;
                default:
                    return 2;
            }
        }
    }
}
