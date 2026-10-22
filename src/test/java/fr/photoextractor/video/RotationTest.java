package fr.photoextractor.video;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RotationTest {

    @Test
    void displayMatrixAngleIsCounterClockwiseSoClockwiseIsItsOpposite() {
        assertEquals(90, Rotation.clockwiseDegrees(-90.0));
        assertEquals(270, Rotation.clockwiseDegrees(90.0));
        assertEquals(180, Rotation.clockwiseDegrees(180.0));
        assertEquals(180, Rotation.clockwiseDegrees(-180.0));
        assertEquals(0, Rotation.clockwiseDegrees(0.0));
    }

    @Test
    void clockwiseDegreesSnapsToQuarterTurns() {
        assertEquals(90, Rotation.clockwiseDegrees(-89.97));
        assertEquals(0, Rotation.clockwiseDegrees(Double.NaN));
    }

    @Test
    void rotate90MovesTopLeftPixelToTopRight() {
        BufferedImage src = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, 0xFF0000);

        BufferedImage out = Rotation.rotate(src, 90);

        assertEquals(2, out.getWidth());
        assertEquals(4, out.getHeight());
        assertEquals(0xFF0000, out.getRGB(1, 0) & 0xFFFFFF);
    }

    @Test
    void rotate180MovesTopLeftPixelToBottomRight() {
        BufferedImage src = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, 0xFF0000);

        BufferedImage out = Rotation.rotate(src, 180);

        assertEquals(4, out.getWidth());
        assertEquals(0xFF0000, out.getRGB(3, 1) & 0xFFFFFF);
    }

    @Test
    void rotate270MovesTopLeftPixelToBottomLeft() {
        BufferedImage src = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, 0xFF0000);

        BufferedImage out = Rotation.rotate(src, 270);

        assertEquals(2, out.getWidth());
        assertEquals(0xFF0000, out.getRGB(0, 3) & 0xFFFFFF);
    }

    @Test
    void rotate0ReturnsSameImage() {
        BufferedImage src = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        assertEquals(src, Rotation.rotate(src, 0));
    }
}
