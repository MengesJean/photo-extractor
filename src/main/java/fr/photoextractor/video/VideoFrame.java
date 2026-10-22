package fr.photoextractor.video;

/** A decoded video frame ready for display: BGRA pixels, tightly packed (width * 4 bytes per row). */
public record VideoFrame(long timestamp, int width, int height, byte[] bgra) {
}
