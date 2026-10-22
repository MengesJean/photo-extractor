package fr.photoextractor.photos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Talks to the macOS Photos app through osascript (AppleScript / JavaScript for Automation). */
public class PhotosLibrary {

    private static final List<String> VIDEO_EXTENSIONS = List.of(".mov", ".mp4", ".m4v");

    // Bulk property reads are much faster than iterating media items one by one.
    private static final String LIST_VIDEOS_JXA = """
            function run() {
              const items = Application('Photos').mediaItems;
              const ids = items.id(), names = items.filename(), dates = items.date();
              const out = [];
              for (let i = 0; i < ids.length; i++) {
                const name = names[i] || '';
                if (/\\.(mov|mp4|m4v)$/i.test(name)) {
                  out.push(ids[i] + '\\t' + name + '\\t' + (dates[i] ? dates[i].toISOString() : ''));
                }
              }
              return out.join('\\n');
            }
            """;

    // Long timeout: Photos may have to download the original from iCloud first.
    private static final String EXPORT_ORIGINAL_APPLESCRIPT = """
            on run argv
              set theId to item 1 of argv
              set dest to (POSIX file (item 2 of argv)) as alias
              tell application "Photos"
                with timeout of 3600 seconds
                  export {media item id theId} to dest with using originals
                end timeout
              end tell
            end run
            """;

    private static final String IMPORT_APPLESCRIPT = """
            on run argv
              set f to (POSIX file (item 1 of argv)) as alias
              tell application "Photos"
                with timeout of 600 seconds
                  import {f} skip check duplicates true
                end timeout
              end tell
            end run
            """;

    /** Lists the videos of the Photos library, most recent first. */
    public List<PhotoVideo> listVideos() throws IOException, InterruptedException {
        return parseVideoList(osascript("JavaScript", LIST_VIDEOS_JXA));
    }

    /** Exports the original file of a video into a fresh temporary folder and returns its path. */
    public Path exportOriginal(PhotoVideo video) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("photo-extractor-");
        osascript("AppleScript", EXPORT_ORIGINAL_APPLESCRIPT, video.id(), dir.toString());
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> isVideoFile(p.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Photos n'a exporté aucun fichier vidéo pour " + video.filename()));
        }
    }

    /** Adds an image file to the Photos library. */
    public void importImage(Path image) throws IOException, InterruptedException {
        osascript("AppleScript", IMPORT_APPLESCRIPT, image.toAbsolutePath().toString());
    }

    static List<PhotoVideo> parseVideoList(String output) {
        List<PhotoVideo> videos = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 3 || parts[0].isBlank()) {
                continue;
            }
            videos.add(new PhotoVideo(parts[0], parts[1], parseDate(parts[2].strip())));
        }
        videos.sort(Comparator.comparing(PhotoVideo::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return videos;
    }

    static boolean isVideoFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static Instant parseDate(String text) {
        try {
            return text.isEmpty() ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String osascript(String language, String script, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("osascript", "-l", language, "-"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        String stdout = readAll(process.getInputStream());
        if (process.waitFor() != 0) {
            throw new IOException(describeError(stderr.join()));
        }
        return stdout.strip();
    }

    private static String describeError(String stderr) {
        if (stderr.contains("-1743")) {
            return "macOS refuse l'accès à Photos. Autorisez-le dans Réglages Système > "
                    + "Confidentialité et sécurité > Automatisation.";
        }
        return "Erreur Photos : " + stderr.strip();
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
