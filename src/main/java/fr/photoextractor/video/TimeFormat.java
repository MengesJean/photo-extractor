package fr.photoextractor.video;

/** Formats video timestamps (in microseconds) for display and file names. */
public final class TimeFormat {

    private TimeFormat() {
    }

    /** "mm:ss.SSS", or "h:mm:ss.SSS" past one hour. */
    public static String display(long micros) {
        long ms = Math.max(0, micros) / 1000;
        long h = ms / 3_600_000, m = ms / 60_000 % 60, s = ms / 1000 % 60, milli = ms % 1000;
        return h > 0
                ? String.format("%d:%02d:%02d.%03d", h, m, s, milli)
                : String.format("%02d:%02d.%03d", m, s, milli);
    }

    /** "00m12s345", or "01h00m05s000" past one hour. */
    public static String fileSuffix(long micros) {
        long ms = Math.max(0, micros) / 1000;
        long h = ms / 3_600_000, m = ms / 60_000 % 60, s = ms / 1000 % 60, milli = ms % 1000;
        String base = String.format("%02dm%02ds%03d", m, s, milli);
        return h > 0 ? String.format("%02dh", h) + base : base;
    }
}
