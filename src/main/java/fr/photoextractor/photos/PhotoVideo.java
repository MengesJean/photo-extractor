package fr.photoextractor.photos;

import java.time.Instant;

/** A video item of the Photos library. {@code date} may be null. */
public record PhotoVideo(String id, String filename, Instant date) {
}
