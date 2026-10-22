package fr.photoextractor.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void displayFormatsMinutesSecondsAndMillis() {
        assertEquals("01:02.345", TimeFormat.display(62_345_000L));
    }

    @Test
    void displayIncludesHoursWhenNeeded() {
        assertEquals("1:00:05.000", TimeFormat.display(3_605_000_000L));
    }

    @Test
    void displayClampsNegativeToZero() {
        assertEquals("00:00.000", TimeFormat.display(-5L));
    }

    @Test
    void fileSuffixIsSafeForFileNames() {
        assertEquals("00m12s345", TimeFormat.fileSuffix(12_345_678L));
    }

    @Test
    void fileSuffixIncludesHoursWhenNeeded() {
        assertEquals("01h00m05s000", TimeFormat.fileSuffix(3_605_000_000L));
    }
}
