package fr.photoextractor.video;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** Handles the display rotation stored in videos (e.g. iPhone portrait clips). */
public final class Rotation {

    private Rotation() {
    }

    /**
     * Converts the counter-clockwise angle of a video's display matrix
     * (as returned by FFmpegFrameGrabber#getDisplayRotation) to the clockwise
     * quarter turn to apply: 0, 90, 180 or 270.
     */
    public static int clockwiseDegrees(double displayMatrixAngle) {
        if (Double.isNaN(displayMatrixAngle)) {
            return 0;
        }
        long quarter = Math.round(-displayMatrixAngle / 90.0);
        return (int) Math.floorMod(quarter * 90, 360);
    }

    /** Rotates the image clockwise by a quarter-turn multiple. */
    public static BufferedImage rotate(BufferedImage src, int clockwiseDegrees) {
        int deg = Math.floorMod(clockwiseDegrees, 360);
        if (deg == 0) {
            return src;
        }
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = deg == 90 || deg == 270;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        AffineTransform t = new AffineTransform();
        switch (deg) {
            case 90 -> t.translate(h, 0);
            case 180 -> t.translate(w, h);
            case 270 -> t.translate(0, w);
            default -> throw new IllegalArgumentException("Not a quarter turn: " + clockwiseDegrees);
        }
        t.rotate(Math.toRadians(deg));
        Graphics2D g = out.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }
}
