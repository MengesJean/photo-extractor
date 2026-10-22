package fr.photoextractor.video;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ShortBuffer;
import java.nio.file.Path;

/** Generates small test videos where frame {@code i} is a uniform gray of level {@link #grayOf(int)}. */
final class TestVideos {

    static final int WIDTH = 64;
    static final int HEIGHT = 48;
    static final int H264_WIDTH = 320;
    static final int H264_HEIGHT = 240;
    static final int SAMPLE_RATE = 44_100;

    private TestVideos() {
    }

    static int grayOf(int frameIndex) {
        return 20 + frameIndex * 7 % 231;
    }

    static Path grayRamp(Path file, int frames, int fps, boolean withAudio, double displayRotation) throws Exception {
        return record(file, WIDTH, HEIGHT, frames, fps, withAudio, displayRotation, false);
    }

    /** Same gray ramp, H.264-encoded by VideoToolbox so that it can be decoded by the hardware. */
    static Path h264GrayRamp(Path file, int frames, int fps) throws Exception {
        return record(file, H264_WIDTH, H264_HEIGHT, frames, fps, false, 0, true);
    }

    private static Path record(Path file, int width, int height, int frames, int fps, boolean withAudio,
                               double displayRotation, boolean h264) throws Exception {
        try (FFmpegFrameRecorder rec = new FFmpegFrameRecorder(file.toFile(), width, height, withAudio ? 1 : 0);
             Java2DFrameConverter conv = new Java2DFrameConverter()) {
            rec.setFormat(file.toString().endsWith(".mov") ? "mov" : "mp4");
            if (h264) {
                rec.setVideoCodecName("h264_videotoolbox");
                rec.setVideoBitrate(4_000_000);
            } else {
                rec.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
                rec.setVideoQuality(1);
            }
            rec.setFrameRate(fps);
            rec.setGopSize(5);
            if (withAudio) {
                rec.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                rec.setSampleRate(SAMPLE_RATE);
            }
            // Packets are written in call order: 0.5 s of pictures, then 0.5 s of sound, like iPhone files.
            rec.setInterleaved(false);
            if (displayRotation != 0) {
                rec.setDisplayRotation(displayRotation);
            }
            rec.start();
            int chunk = Math.max(1, fps / 2);
            for (int i = 0; i < frames; i++) {
                rec.record(conv.convert(solid(grayOf(i), width, height)));
                boolean chunkDone = (i + 1) % chunk == 0 || i == frames - 1;
                if (withAudio && chunkDone) {
                    int chunkFrames = i % chunk + 1;
                    rec.recordSamples(SAMPLE_RATE, 1, ShortBuffer.wrap(new short[SAMPLE_RATE * chunkFrames / fps]));
                }
            }
        }
        return file;
    }

    /** Average gray level of the image center. */
    static int centerGray(BufferedImage img) {
        int rgb = img.getRGB(img.getWidth() / 2, img.getHeight() / 2);
        return (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
    }

    /** Average gray level of the center of a BGRA frame. */
    static int centerGray(VideoFrame frame) {
        int i = ((frame.height() / 2) * frame.width() + frame.width() / 2) * 4;
        byte[] p = frame.bgra();
        return ((p[i] & 0xFF) + (p[i + 1] & 0xFF) + (p[i + 2] & 0xFF)) / 3;
    }

    private static BufferedImage solid(int gray, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(gray, gray, gray));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }
}
