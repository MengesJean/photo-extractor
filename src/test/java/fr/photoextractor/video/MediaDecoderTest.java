package fr.photoextractor.video;

import fr.photoextractor.video.MediaDecoder.Decoded;
import fr.photoextractor.video.MediaDecoder.Picture;
import fr.photoextractor.video.MediaDecoder.Sound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDecoderTest {

    @TempDir
    Path tmp;

    private MediaDecoder decoder;

    @AfterEach
    void close() {
        if (decoder != null) {
            decoder.close();
        }
    }

    @Test
    void reportsStreamInformation() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mov"), 30, 10, true, 90);
        decoder = MediaDecoder.open(video, 1280);

        assertEquals(3_000_000L, decoder.durationMicros(), 150_000L);
        assertEquals(10.0, decoder.frameRate(), 0.01);
        assertEquals(grabberRotation(video), decoder.displayRotation(), 0.01);
        assertTrue(decoder.displayRotation() != 0);
        assertTrue(decoder.hasAudio());
        assertEquals(TestVideos.SAMPLE_RATE, decoder.sampleRate());
        assertEquals(1, decoder.audioChannels());
    }

    /** The preview must turn like the extracted frame, which FFmpegFrameGrabber decodes. */
    private static double grabberRotation(Path video) throws Exception {
        try (org.bytedeco.javacv.FFmpegFrameGrabber g = new org.bytedeco.javacv.FFmpegFrameGrabber(video.toFile())) {
            g.start();
            return g.getDisplayRotation();
        }
    }

    @Test
    void decodesPicturesInOrderAsBgra() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 5, 10, false, 0), 1280);

        VideoFrame first = decoder.nextPicture();
        VideoFrame second = decoder.nextPicture();

        assertEquals(0, first.timestamp());
        assertEquals(100_000L, second.timestamp(), 1_000L);
        assertEquals(TestVideos.WIDTH, first.width());
        assertEquals(TestVideos.WIDTH * TestVideos.HEIGHT * 4, first.bgra().length);
        assertEquals(TestVideos.grayOf(1), TestVideos.centerGray(second), 4);
    }

    @Test
    void returnsNullAtTheEnd() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 3, 10, false, 0), 1280);

        for (int i = 0; i < 3; i++) {
            assertNotNull(decoder.nextPicture());
        }
        assertNull(decoder.nextPicture());
        assertNull(decoder.next(true, Long.MIN_VALUE));
    }

    @Test
    void scalesPicturesDownToTheMaximumSize() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 3, 10, false, 0), 32);

        VideoFrame frame = decoder.nextPicture();

        assertEquals(32, frame.width());
        assertEquals(24, frame.height());
    }

    @Test
    void seekPositionsOnTheFrameShownAtTheTimestamp() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0), 1280);

        decoder.seek(1_550_000L);
        VideoFrame atTarget = decoder.nextPicture();
        VideoFrame after = decoder.nextPicture();
        decoder.seek(300_000L);
        VideoFrame backwards = decoder.nextPicture();

        assertEquals(1_500_000L, atTarget.timestamp(), 1_000L);
        assertEquals(TestVideos.grayOf(15), TestVideos.centerGray(atTarget), 4);
        assertEquals(TestVideos.grayOf(16), TestVideos.centerGray(after), 4);
        assertEquals(TestVideos.grayOf(3), TestVideos.centerGray(backwards), 4);
    }

    @Test
    void interleavesSoundAndPictures() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mov"), 30, 10, true, 0), 1280);
        int pictures = 0, soundBytes = 0;

        for (Decoded d = decoder.next(true, Long.MIN_VALUE); d != null; d = decoder.next(true, Long.MIN_VALUE)) {
            if (d instanceof Picture) {
                pictures++;
            } else if (d instanceof Sound s) {
                soundBytes += s.pcm().length;
            }
        }

        assertEquals(30, pictures);
        // 3 s of 16-bit mono at 44.1 kHz, give or take the encoder padding.
        assertEquals(3 * TestVideos.SAMPLE_RATE * 2, soundBytes, TestVideos.SAMPLE_RATE / 5);
    }

    @Test
    void skipsPicturesBeforeTheMinimumTimestamp() throws Exception {
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 10, 10, false, 0), 1280);

        Decoded d = decoder.next(false, 450_000L);

        assertEquals(500_000L, ((Picture) d).frame().timestamp(), 1_000L);
    }

    @Test
    void keepsTheSoundThatFollowsTheSeekTarget() throws Exception {
        // In chunked files the sound for the target time may be read while looking for the picture.
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mov"), 60, 30, true, 0), 1280);

        decoder.seek(1_100_000L);
        Sound firstSound = null;
        for (Decoded d = decoder.next(true, Long.MIN_VALUE); firstSound == null; d = decoder.next(true, Long.MIN_VALUE)) {
            if (d instanceof Sound s) {
                firstSound = s;
            }
        }

        assertEquals(1_100_000L, firstSound.timestamp(), 60_000L);
    }

    @Test
    void decodesH264WithTheHardwareDecoder() throws Exception {
        decoder = MediaDecoder.open(TestVideos.h264GrayRamp(tmp.resolve("v.mp4"), 10, 10), 1280);

        decoder.seek(500_000L);
        VideoFrame frame = decoder.nextPicture();

        assertTrue(decoder.hardwareAccelerated());
        assertEquals(TestVideos.H264_WIDTH, frame.width());
        assertEquals(TestVideos.grayOf(5), TestVideos.centerGray(frame), 6);
    }

    @Test
    void otherCodecsAreDecodedInSoftware() throws Exception {
        // Hardware decoding is only used for H.264 and HEVC, where it matters.
        decoder = MediaDecoder.open(TestVideos.grayRamp(tmp.resolve("v.mp4"), 3, 10, false, 0), 1280);

        decoder.nextPicture();

        assertFalse(decoder.hardwareAccelerated());
    }
}
