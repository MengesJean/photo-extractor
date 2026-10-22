package fr.photoextractor.photos;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotosLibraryTest {

    @Test
    void parsesTabSeparatedVideoLines() {
        String out = "ABC/L0/001\tIMG_0001.MOV\t2024-05-01T10:15:30.000Z\n"
                   + "DEF/L0/001\tclip.mp4\t2023-01-02T03:04:05.000Z\n";

        List<PhotoVideo> videos = PhotosLibrary.parseVideoList(out);

        assertEquals(2, videos.size());
        assertEquals(new PhotoVideo("ABC/L0/001", "IMG_0001.MOV", Instant.parse("2024-05-01T10:15:30Z")), videos.get(0));
        assertEquals("clip.mp4", videos.get(1).filename());
    }

    @Test
    void sortsMostRecentFirst() {
        String out = "A\told.mov\t2020-01-01T00:00:00.000Z\n"
                   + "B\tnew.mov\t2024-01-01T00:00:00.000Z\n";

        List<PhotoVideo> videos = PhotosLibrary.parseVideoList(out);

        assertEquals("new.mov", videos.get(0).filename());
    }

    @Test
    void keepsVideosWithoutDateAtTheEnd() {
        String out = "A\tnodate.mov\t\n"
                   + "B\tdated.mov\t2024-01-01T00:00:00.000Z\n";

        List<PhotoVideo> videos = PhotosLibrary.parseVideoList(out);

        assertEquals("dated.mov", videos.get(0).filename());
        assertNull(videos.get(1).date());
    }

    @Test
    void ignoresBlankAndMalformedLines() {
        String out = "\n" + "garbage\n" + "A\tok.mov\t2024-01-01T00:00:00.000Z\n";

        assertEquals(1, PhotosLibrary.parseVideoList(out).size());
    }

    @Test
    void emptyOutputGivesEmptyList() {
        assertTrue(PhotosLibrary.parseVideoList("").isEmpty());
    }

    @Test
    void recognisesVideoExtensionsCaseInsensitively() {
        assertTrue(PhotosLibrary.isVideoFile("IMG_1.MOV"));
        assertTrue(PhotosLibrary.isVideoFile("a.mp4"));
        assertTrue(PhotosLibrary.isVideoFile("a.M4V"));
        assertEquals(false, PhotosLibrary.isVideoFile("IMG_1.HEIC"));
        assertEquals(false, PhotosLibrary.isVideoFile("IMG_1.AAE"));
    }
}
