package fr.photoextractor.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackClockTest {

    private final AtomicLong audioPlayed = new AtomicLong(5_000);
    private final PlaybackClock clock = new PlaybackClock(audioPlayed::get);

    @Test
    void followsTheAudioDeviceFromTheResetPosition() {
        clock.reset(1_000_000, true);
        audioPlayed.addAndGet(40_000);

        assertEquals(1_040_000, clock.now());
    }

    @Test
    void anchorsOnTheTimestampOfTheFirstSoundPlayed() {
        // After a seek the first sound available may start a little after the displayed picture.
        clock.reset(1_000_000, true);
        clock.anchorAudio(1_120_000);
        audioPlayed.addAndGet(40_000);

        assertEquals(1_160_000, clock.now());
    }

    @Test
    void onlyTheFirstSoundAnchorsTheClock() {
        clock.reset(1_000_000, true);
        clock.anchorAudio(1_120_000);
        clock.anchorAudio(1_500_000);

        assertEquals(1_120_000, clock.now());
    }

    @Test
    void pauseFreezesAndResumeContinuesFromTheSameTime() {
        clock.reset(1_000_000, true);
        clock.anchorAudio(1_120_000);
        audioPlayed.addAndGet(40_000);
        clock.pause();
        audioPlayed.addAndGet(10_000);
        long paused = clock.now();
        clock.resume();
        audioPlayed.addAndGet(30_000);

        assertEquals(1_160_000, paused);
        assertEquals(1_190_000, clock.now());
    }
}
