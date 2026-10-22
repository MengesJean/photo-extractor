package fr.photoextractor.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameExtractorTest {

    @TempDir
    Path tmp;

    @Test
    void extractsTheFrameDisplayedAtTheTimestamp() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0);

        BufferedImage img = FrameExtractor.extract(video, 1_050_000L);

        assertEquals(TestVideos.grayOf(10), TestVideos.centerGray(img), 4);
    }

    @Test
    void extractsAtFullResolution() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mp4"), 5, 10, false, 0);

        BufferedImage img = FrameExtractor.extract(video, 0);

        assertEquals(TestVideos.WIDTH, img.getWidth());
        assertEquals(TestVideos.HEIGHT, img.getHeight());
    }

    @Test
    void appliesTheVideoDisplayRotation() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mp4"), 5, 10, false, 90);

        BufferedImage img = FrameExtractor.extract(video, 0);

        assertEquals(TestVideos.HEIGHT, img.getWidth());
        assertEquals(TestVideos.WIDTH, img.getHeight());
    }
}
