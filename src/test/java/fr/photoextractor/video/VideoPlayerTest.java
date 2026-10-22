package fr.photoextractor.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPlayerTest {

    @TempDir
    Path tmp;

    private final RecordingListener listener = new RecordingListener();
    private VideoPlayer player;

    @AfterEach
    void close() {
        if (player != null) {
            player.close();
        }
    }

    @Test
    void showsTheFirstFrameWhenOpened() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);

        VideoFrame first = listener.nextFrame();

        assertEquals(0, first.timestamp());
        assertEquals(TestVideos.grayOf(0), TestVideos.centerGray(first), 4);
        assertFalse(player.isPlaying());
    }

    @Test
    void reportsDurationAndFrameRate() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);

        assertEquals(3_000_000L, player.durationMicros(), 150_000L);
        assertEquals(10.0, player.frameRate(), 0.01);
    }

    @Test
    void seekShowsTheFrameAtTheTimestamp() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);
        listener.nextFrame();

        player.seek(1_550_000L);
        VideoFrame frame = listener.nextFrame();

        assertEquals(1_500_000L, frame.timestamp(), 1_000L);
        assertEquals(TestVideos.grayOf(15), TestVideos.centerGray(frame), 4);
        assertEquals(frame.timestamp(), player.positionMicros());
    }

    @Test
    void burstOfSeeksJumpsToTheLastTargetWithoutDecodingEachOne() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);
        listener.nextFrame();

        for (int i = 0; i <= 20; i++) {
            player.seek(i * 100_000L);
        }
        Thread.sleep(500);

        assertTrue(listener.frames.size() < 5, "decoded " + listener.frames.size() + " seeks");
        assertEquals(2_000_000L, player.positionMicros(), 1_000L);
    }

    @Test
    void stepsOneFrameForwardAndBackward() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);
        listener.nextFrame();
        player.seek(1_000_000L);
        listener.nextFrame();

        player.stepForward();
        VideoFrame next = listener.nextFrame();
        player.stepBackward();
        VideoFrame back = listener.nextFrame();
        player.stepBackward();
        VideoFrame back2 = listener.nextFrame();

        assertEquals(TestVideos.grayOf(11), TestVideos.centerGray(next), 4);
        assertEquals(TestVideos.grayOf(10), TestVideos.centerGray(back), 4);
        assertEquals(TestVideos.grayOf(9), TestVideos.centerGray(back2), 4);
    }

    @Test
    void stepBackwardAtStartStaysOnFirstFrame() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280, listener);
        listener.nextFrame();

        player.stepBackward();

        assertEquals(TestVideos.grayOf(0), TestVideos.centerGray(listener.nextFrame()), 4);
    }

    @Test
    void scalesDisplayFramesDownToTheMaximumSize() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 5, 10, false, 0), 32, listener);

        VideoFrame frame = listener.nextFrame();

        assertEquals(32, frame.width());
        assertEquals(24, frame.height());
        assertEquals(32 * 24 * 4, frame.bgra().length);
    }

    @Test
    void playsInRealTimeUntilTheEndThenPauses() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 10, 10, true, 0), 1280, listener);
        listener.nextFrame();
        long start = System.nanoTime();

        player.play();

        assertTrue(listener.ended.await(5, TimeUnit.SECONDS), "playback should reach the end");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 700, "played too fast: " + elapsedMs + " ms");
        assertFalse(player.isPlaying());
        assertEquals(900_000L, player.positionMicros(), 1_000L);
    }

    @Test
    void playsHighFrameRateVideoWithSoundInRealTime() throws Exception {
        // Like iPhone 120 fps clips: many pictures sit between two audio chunks in the file.
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mov"), 240, 120, true, 0), 1280, listener);
        listener.nextFrame();
        long start = System.nanoTime();

        player.play();

        assertTrue(listener.ended.await(6, TimeUnit.SECONDS), "playback should reach the end");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 1_700 && elapsedMs <= 2_800, "2 s clip played in " + elapsedMs + " ms");
    }

    @Test
    void pauseStopsTheFrames() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 50, 10, false, 0), 1280, listener);
        listener.nextFrame();

        player.play();
        listener.nextFrame();
        player.pause();
        Thread.sleep(150);
        listener.frames.clear();
        long pausedAt = player.positionMicros();
        Thread.sleep(400);

        assertNull(listener.frames.poll());
        assertEquals(pausedAt, player.positionMicros());
        assertFalse(player.isPlaying());
    }

    @Test
    void resumesAfterPauseWhereItStopped() throws Exception {
        player = VideoPlayer.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 50, 10, false, 0), 1280, listener);
        listener.nextFrame();
        player.seek(2_000_000L);
        listener.nextFrame();

        player.play();
        VideoFrame frame = listener.nextFrame();

        assertTrue(frame.timestamp() >= 2_000_000L && frame.timestamp() <= 2_300_000L,
                "unexpected first frame after play: " + frame.timestamp());
    }

    private static final class RecordingListener implements VideoPlayer.Listener {
        final BlockingQueue<VideoFrame> frames = new LinkedBlockingQueue<>();
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public void onFrame(VideoFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onEnd() {
            ended.countDown();
        }

        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }

        VideoFrame nextFrame() throws InterruptedException {
            VideoFrame f = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(f, "no frame received");
            return f;
        }
    }
}
