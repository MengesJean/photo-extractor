package fr.photoextractor.photos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThumbnailLocatorTest {

    private static final String UUID = "6FF7717D-406C-487F-A4A9-9F5874227D2B";

    @TempDir
    Path derivatives;

    private PhotoVideo video(String id) {
        return new PhotoVideo(id, "IMG_6682.MOV", null);
    }

    private Path create(String relative) throws Exception {
        Path file = derivatives.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "jpeg");
    }

    @Test
    void findsTheMasterThumbnailFromThePhotosId() throws Exception {
        Path expected = create("masters/6/" + UUID + "_4_5005_c.jpeg");

        assertEquals(Optional.of(expected), new ThumbnailLocator(derivatives).find(video(UUID + "/L0/001")));
    }

    @Test
    void fallsBackToTheSmallDerivative() throws Exception {
        Path expected = create("6/" + UUID + "_1_105_c.jpeg");

        assertEquals(Optional.of(expected), new ThumbnailLocator(derivatives).find(video(UUID + "/L0/001")));
    }

    @Test
    void prefersTheMasterThumbnail() throws Exception {
        create("6/" + UUID + "_1_105_c.jpeg");
        Path master = create("masters/6/" + UUID + "_4_5005_c.jpeg");

        assertEquals(Optional.of(master), new ThumbnailLocator(derivatives).find(video(UUID + "/L0/001")));
    }

    @Test
    void reportsMissingThumbnails() {
        assertEquals(Optional.empty(), new ThumbnailLocator(derivatives).find(video(UUID + "/L0/001")));
    }

    @Test
    void ignoresIdsThatCannotNameAFile() {
        ThumbnailLocator locator = new ThumbnailLocator(derivatives);

        assertEquals(Optional.empty(), locator.find(video("")));
        assertEquals(Optional.empty(), locator.find(video("../../etc/L0/001")));
    }
}
