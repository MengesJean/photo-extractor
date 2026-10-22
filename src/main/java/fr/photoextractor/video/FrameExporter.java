package fr.photoextractor.video;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Turns a video frame into a JPEG and hands it to an importer (the Photos library). */
public final class FrameExporter {

    private static final float JPEG_QUALITY = 0.95f;

    @FunctionalInterface
    public interface Importer {
        void importImage(Path image) throws Exception;
    }

    private FrameExporter() {
    }

    /** Extracts the frame at {@code micros}, writes it as a temporary JPEG, imports it, then deletes the file. */
    public static void export(Path video, String videoFilename, long micros, Importer importer) throws Exception {
        BufferedImage image = FrameExtractor.extract(video, micros);
        Path dir = Files.createTempDirectory("photo-extractor-frame-");
        Path jpeg = dir.resolve(fileName(videoFilename, micros));
        try {
            writeJpeg(image, jpeg);
            importer.importImage(jpeg);
        } finally {
            Files.deleteIfExists(jpeg);
            Files.deleteIfExists(dir);
        }
    }

    public static String fileName(String videoFilename, long micros) {
        int dot = videoFilename.lastIndexOf('.');
        String base = dot > 0 ? videoFilename.substring(0, dot) : videoFilename;
        return base + "_frame_" + TimeFormat.fileSuffix(micros) + ".jpg";
    }

    private static void writeJpeg(BufferedImage image, Path file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
