package fr.photoextractor.photos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the thumbnails Photos keeps inside its library bundle, so the video grid can show
 * previews without exporting anything.
 */
public final class ThumbnailLocator {

    private static final Pattern UUID = Pattern.compile("[0-9A-Fa-f-]{36}");

    private final Path derivatives;

    public ThumbnailLocator(Path derivatives) {
        this.derivatives = derivatives;
    }

    /** The system Photos library of the current user. */
    public static ThumbnailLocator forDefaultLibrary() {
        return new ThumbnailLocator(Path.of(System.getProperty("user.home"),
                "Pictures", "Photos Library.photoslibrary", "resources", "derivatives"));
    }

    public Optional<Path> find(PhotoVideo video) {
        // Media item ids look like "6FF7717D-406C-487F-A4A9-9F5874227D2B/L0/001".
        String uuid = video.id().split("/", 2)[0];
        if (!UUID.matcher(uuid).matches()) {
            return Optional.empty();
        }
        String folder = uuid.substring(0, 1);
        Path master = derivatives.resolve("masters").resolve(folder).resolve(uuid + "_4_5005_c.jpeg");
        Path small = derivatives.resolve(folder).resolve(uuid + "_1_105_c.jpeg");
        if (Files.isRegularFile(master)) {
            return Optional.of(master);
        }
        return Files.isRegularFile(small) ? Optional.of(small) : Optional.empty();
    }
}
