# Synth Spyglass for Android

☕ If this helps you make music, you can [buy me a coffee on Ko-fi](https://ko-fi.com/aikellogg).

Play your synth from a USB keyboard through an **Android tablet or phone**, and watch every note in
a **spyglass**: a round, see-through window that floats over every app (a YouTube lesson, Skoove,
anything) and shows the note or chord big, the notes on a staff, how hard you hit the key, and the
synth's **real waveform**.

Made for the M-VAVE FM-1, which needs a computer or tablet in between to play from a USB-only
keyboard. Free. The Android twin of [Synth Spyglass](https://youtu.be/KoUeE4bOoy0) on the Mac.

**Watch it:** [Synth Spyglass Now on Android](https://youtu.be/jbxL0yW96QM) (1:18)

**Want the FM-1's screen big on the tablet too?** [FM-1 Mirror + Spyglass](https://github.com/pfkellogg/fm1-mirror-spyglass-android) is one app with the
FM-1's screen, the MIDI bridge and the spyglass ([video](https://youtu.be/XDqrabQf5uY)). On Android only one app at a time
can play the FM-1, so use that one instead of this one when you want the big screen.

Questions, or something not working? Write to me: **pfkellogg@gmail.com**

**No FM-1?** [MIDI Spyglass](https://github.com/pfkellogg/midi-spyglass) is the spyglass on its own, for any MIDI keyboard: the notes and chords, the staff, the waveform, and FROM → TO pairs that play your synths (Mac, Windows, Linux and Android; [video](https://youtu.be/GMLf3W4oTp4)).

## Download

[**Synth-Spyglass-android.apk**](https://github.com/pfkellogg/synth-spyglass-android/releases/latest/download/Synth-Spyglass-android.apk)
(Android 10 or newer)

Open the link on the tablet or phone and tap the file when it has downloaded. It isn't from Google
Play, so Android asks first: allow your browser to **install unknown apps**, then tap **Install**.

> **Not the green "Code" button.** GitHub's green **Code → Download ZIP** gives the app's source
> code, which doesn't install. Use the link above, or **Releases** on the right of the page.

## Plugging it in

Your tablet's USB-C port takes a small **USB hub** (one with its own power input is best, so the
tablet charges too). Into the hub:

- the **keyboard** (any USB MIDI keyboard: an M-Audio Keystation, an Arturia MiniLab…)
- the **M-VAVE FM-1** (or a USB MIDI interface whose 5-pin MIDI OUT goes to another synth)

The FM-1's sound still comes out of its own headphone jack, as always.

## Using it

1. Open **Synth Spyglass**. It finds the keyboard (**FROM**) and the FM-1 (**TO**) by itself; tap
   either one to choose another.
2. Tap **ON**. Play: the FM-1 plays, and the notes show in the app.
3. Tap **◉ Spyglass** for the round window over every app. The first time Android asks two things:
   - **Display over other apps:** switch it on for Synth Spyglass and come back. If Android says
     **App was denied access** (a "restricted setting", for apps not from Google Play): open
     **Settings › Apps › Synth Spyglass**, tap **⋮** at the top right, **Allow restricted
     settings**, then switch it on.
   - **Record audio:** tap **Allow**. That's how it hears the FM-1's own sound over its USB cable
     for the waveform. Android calls any audio input "the microphone"; the app listens only to the
     FM-1, and only while the spyglass is on.
4. Open your lesson, video or other app: the spyglass stays on top.

From 1.0.2 a note or chord clears 3 seconds after the last key comes up, leaving empty staves over "play a note", and the staves are larger.

## The spyglass

- **Note or chord:** the name big (E, or Cmaj7), the notes on a grand staff, and under them the full
  name and pitch ("E4 329.6 Hz", to one decimal), or the keys held and the interval.
- **Never stuck waiting:** each device is picked up the moment it's plugged in, and the spyglass shows the
  keyboard even while the synth isn't there yet.
- **How hard you hit the key:** "vel 96" and a brass bar, full at 127. It shows once the keyboard has
  sent two different velocities (some keyboards play every note at the same one).
- **The FM-1's real waveform**, beside the staff, standing still like an oscilloscope's, with
  **♪ FM-1** along the top. It's the sound the FM-1 actually makes, whichever voice it's on. (Only
  with the FM-1 on the tablet's USB: through a MIDI interface's 5-pin cable its sound doesn't reach
  the tablet.)
- **Move it:** drag it. **Resize it:** pinch it, or tap it and drag the brass grip on its rim.
- **Put it away:** tap it, then the ✕ at its top; or tap **◉ Spyglass** in the app again.

Where it is, its size and whether it's on are remembered. It keeps working with the app in the
background, with a small "Synth Spyglass is on" notification; **Stop** there, or **ON** in the app,
turns it off.

## Also in the app

- **All notes off**, if a note ever gets stuck.
- **Other apps can play the synth too:** Synth Spyglass offers a MIDI input called "Synth Spyglass"
  that goes to the same synth, for a piano-lesson or karaoke app.
- **A computer's keyboard over Wi-Fi:** FROM **Mac (Wi-Fi)** takes the notes from MIDI Bridge on a
  computer on the same network.
- Keyboards knocked onto another MIDI channel still play: the FM-1 listens on channel 1 only, so
  notes are moved there.

## Works with MIDI Bridge

This app grew out of the Android MIDI Bridge and does everything it does. Use one at a time: only one
app can hold the keyboard (switch MIDI Bridge OFF first). Synth Spyglass leaves MIDI Bridge's own
"Mac (Wi-Fi)" and "MIDI Bridge" devices out of its lists, so it never picks them by mistake.

## Build it yourself

Java 17 and the Android SDK:

```
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## For the curious

- `Bridge.java`: keyboard → synth, with held-note tracking for All notes off and chord names.
- `Spyglass.java`: the window over other apps; the brass eyepiece is drawn in its `GlassView`.
- `Scope.java`: the FM-1's USB sound, kept for the waveform. Two test switches, files in the app's
  folder `Android/data/com.pfkellogg.synthspyglass/files` (`adb push`): `scope-any-input` makes any
  audio input count (for an emulator), and `save-sound` also saves what it hears there as
  `fm1-<time>.wav` (for making videos: the same sound the waveform shows).
- `StaffView.java`, `Chord.java`: the staff and the chord names.
- `WifiMidiService.java`, `BridgeMidiService.java`: the "Mac (Wi-Fi)" and "Synth Spyglass" MIDI devices.

## License

MIT. © 2026 Patrick F. Kellogg. M-VAVE and FM-1 are M-VAVE's names; this app isn't an M-VAVE product.
