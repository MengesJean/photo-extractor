package fr.photoextractor.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Decodes a single frame of a video at full resolution, independently of playback. */
public final class FrameExtractor {

    private FrameExtractor() {
    }

    /** Returns the frame shown at {@code micros}, upright (display rotation applied). */
    public static BufferedImage extract(Path video, long micros) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            StreamSelector.disableAudio(grabber);
            grabber.start();
            grabber.setVideoTimestamp(Math.max(0, micros));
            Frame frame = grabber.grabImage();
            if (frame == null) {
                throw new IOException("Aucune image à " + TimeFormat.display(micros));
            }
            // The converter reuses its buffer: copy before it is closed.
            BufferedImage image = Java2DFrameConverter.cloneBufferedImage(converter.convert(frame));
            return Rotation.rotate(image, Rotation.clockwiseDegrees(grabber.getDisplayRotation()));
        }
    }
}
