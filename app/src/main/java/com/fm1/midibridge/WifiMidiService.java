package com.fm1.midibridge;

import android.content.Context;
import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * Publishes a MIDI device named "Mac (Wi-Fi)" with one output port: the MIDI
 * a computer's MIDI Bridge broadcasts on the home network when its TO
 * "Tablet & TV (Wi-Fi)" is ticked (rust_midi_bridge/src/wifi.rs). Pick it as
 * FROM and the computer's keyboard plays here like one plugged into the tablet.
 *
 * UDP port 47813 (see wifi.rs): "FM1B" = a computer saying it's there, and
 * "FM1M"/"FM1K" = MIDI from it; to each we answer "FM1H" (once a second at
 * most) so it sends straight to us, which Wi-Fi retries, instead of broadcasting.
 * "FM1M" + one MIDI message: passed on, minus a key press for a key already down
 * (a repeated packet). "FM1K" + the keys held on the computer: any key we let
 * through that isn't in it gets a note-off, so a lost note-off can't stick.
 *
 * Android starts this service when the bridge opens the device, so it only
 * listens while in use.
 */
public class WifiMidiService extends MidiDeviceService {

    static final int PORT = 47813;

    private DatagramSocket socket;
    private Thread listener;
    private WifiManager.MulticastLock wifiLock;
    private final boolean[] down = new boolean[128];  // keys we've passed on as pressed
    private long helloAt;

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[0];
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Wi-Fi drops broadcasts to save power unless someone asks for them.
        wifiLock = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .createMulticastLock("MidiBridgeWifi");
        wifiLock.acquire();
        listener = new Thread(this::listen, "WifiMidi");
        listener.start();
    }

    @Override
    public void onDestroy() {
        if (socket != null) socket.close();  // ends listen()
        if (wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    private void listen() {
        byte[] buf = new byte[512];
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(PORT));
            Log.i("MidiBridge", "listening for MIDI on the network, port " + PORT);
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                int n = p.getLength();
                if (n < 4 || buf[0] != 'F' || buf[1] != 'M' || buf[2] != '1') continue;
                if (buf[3] != 'B' && buf[3] != 'M' && buf[3] != 'K') continue;
                long now = System.currentTimeMillis();
                if (now - helloAt >= 1000) {
                    helloAt = now;
                    socket.send(new DatagramPacket(new byte[]{'F', 'M', '1', 'H'}, 4, p.getSocketAddress()));
                }
                try {
                    if (buf[3] == 'M' && n > 4) passOn(buf, n);
                    else if (buf[3] == 'K' && n > 4) letGo(buf, n);
                } catch (IOException e) {
                    Log.d("MidiBridge", "wifi midi: " + e);
                }
            }
        } catch (SocketException e) {
            // closed by onDestroy
        } catch (IOException e) {
            Log.w("MidiBridge", "wifi midi listener failed", e);
        }
    }

    private void passOn(byte[] buf, int n) throws IOException {
        int type = buf[4] & 0xF0;
        if (n >= 7 && (type == 0x90 || type == 0x80)) {
            int note = buf[5] & 0x7F;
            boolean press = type == 0x90 && buf[6] != 0;
            if (press == down[note]) return;  // repeated packet: already pressed / already let go
            down[note] = press;
        } else if (n >= 6 && type == 0xB0 && ((buf[5] & 0x7F) == 120 || (buf[5] & 0x7F) == 123)) {
            java.util.Arrays.fill(down, false);
        }
        getOutputPortReceivers()[0].send(buf, 4, n - 4);
    }

    /** "FM1K", count, the keys held on the computer: let go of any other key we pressed. */
    private void letGo(byte[] buf, int n) throws IOException {
        boolean[] held = new boolean[128];
        int count = Math.min(buf[4] & 0xFF, n - 5);
        for (int i = 0; i < count; i++) held[buf[5 + i] & 0x7F] = true;
        for (int note = 0; note < 128; note++) {
            if (!down[note] || held[note]) continue;
            down[note] = false;
            getOutputPortReceivers()[0].send(new byte[]{(byte) 0x80, (byte) note, 0}, 0, 3);
        }
    }
}
