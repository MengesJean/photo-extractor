package fr.photoextractor.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameExporterTest {

    @TempDir
    Path tmp;

    @Test
    void namesTheImageAfterTheVideoAndTimestamp() {
        assertEquals("IMG_0001_frame_00m12s345.jpg", FrameExporter.fileName("IMG_0001.MOV", 12_345_000L));
        assertEquals("my.clip_frame_00m00s000.jpg", FrameExporter.fileName("my.clip.mp4", 0));
    }

    @Test
    void handsAReadableJpegOfTheFrameToTheImporter() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mp4"), 30, 10, false, 0);
        AtomicReference<BufferedImage> imported = new AtomicReference<>();
        AtomicReference<Path> importedPath = new AtomicReference<>();

        FrameExporter.export(video, "IMG_0001.MOV", 2_000_000L, path -> {
            importedPath.set(path);
            imported.set(ImageIO.read(path.toFile()));
        });

        assertEquals("IMG_0001_frame_00m02s000.jpg", importedPath.get().getFileName().toString());
        assertEquals(TestVideos.WIDTH, imported.get().getWidth());
        assertEquals(TestVideos.grayOf(20), TestVideos.centerGray(imported.get()), 4);
    }

    @Test
    void removesTheTemporaryImageAfterImport() throws Exception {
        Path video = TestVideos.grayRamp(tmp.resolve("v.mp4"), 5, 10, false, 0);
        AtomicReference<Path> importedPath = new AtomicReference<>();

        FrameExporter.export(video, "v.mp4", 0, path -> {
            assertTrue(Files.exists(path));
            importedPath.set(path);
        });

        assertFalse(Files.exists(importedPath.get()));
    }
}
