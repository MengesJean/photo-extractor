package fr.photoextractor.video;

import java.util.function.LongSupplier;

/**
 * Media time of the playback, in microseconds. Follows the audio device when there is one,
 * so that pictures stay in sync with the sound; otherwise follows the system clock.
 */
final class PlaybackClock {

    private static final long AUDIO_STALL_MICROS = 150_000;

    private final LongSupplier audioMicros;
    private long base;
    private boolean anchored;
    private long audioStart;
    private long lastAudio;
    private long lastAudioChangeNanos;
    private long wallStartNanos;
    private boolean running;
    private boolean audioExhausted;

    /** @param audioMicros played time of the audio device, or null when there is no audio */
    PlaybackClock(LongSupplier audioMicros) {
        this.audioMicros = audioMicros;
    }

    synchronized void reset(long mediaMicros, boolean run) {
        base = mediaMicros;
        anchored = false;
        audioExhausted = false;
        if (run) {
            start();
        } else {
            running = false;
        }
    }

    synchronized void pause() {
        if (running) {
            base = now();
            anchored = false;
            running = false;
        }
    }

    synchronized void resume() {
        if (!running) {
            start();
        }
    }

    /**
     * Sets the media time of the first sound written to the device since the last reset: after a
     * seek, the sound may start a little after the displayed picture. Later calls are ignored.
     */
    synchronized void anchorAudio(long firstSoundMicros) {
        if (audioMicros != null && !anchored) {
            base = firstSoundMicros;
            anchored = true;
        }
    }

    /** Once the audio track is fully decoded, a silent device must not freeze the pictures. */
    synchronized void setAudioExhausted(boolean exhausted) {
        audioExhausted = exhausted;
    }

    synchronized long now() {
        if (!running) {
            return base;
        }
        long t = System.nanoTime();
        if (audioMicros == null) {
            return base + (t - wallStartNanos) / 1000;
        }
        long a = audioMicros.getAsLong();
        if (a != lastAudio) {
            lastAudio = a;
            lastAudioChangeNanos = t;
        }
        long stalled = (t - lastAudioChangeNanos) / 1000;
        long extra = audioExhausted && stalled > AUDIO_STALL_MICROS ? stalled : 0;
        return base + (a - audioStart) + extra;
    }

    private void start() {
        running = true;
        wallStartNanos = System.nanoTime();
        if (audioMicros != null) {
            audioStart = audioMicros.getAsLong();
            lastAudio = audioStart;
            lastAudioChangeNanos = wallStartNanos;
        }
    }
}
