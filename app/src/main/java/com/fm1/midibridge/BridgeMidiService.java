package com.fm1.midibridge;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;

import java.io.IOException;

/**
 * Publishes the bridge as a MIDI device named "MIDI Bridge" with one input
 * port. Android lets only one app at a time send to a USB synth, so while the
 * bridge holds the FM-1, other apps send here instead and their notes are
 * mixed into the bridge's stream to the TO device.
 */
public class BridgeMidiService extends MidiDeviceService {

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[]{new MidiReceiver() {
            @Override
            public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
                Bridge.get(BridgeMidiService.this).sendFromApp(msg, offset, count);
            }
        }};
    }
}
