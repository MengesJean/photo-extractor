package fr.photoextractor.video;

import fr.photoextractor.video.MediaDecoder.Decoded;
import fr.photoextractor.video.MediaDecoder.Picture;
import fr.photoextractor.video.MediaDecoder.Sound;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays a video file with sound and lets the caller pause, seek and step frame by frame.
 *
 * <p>Two threads: the decoder thread owns the media decoder and the audio device and executes
 * the commands one by one; the presenter thread hands each decoded picture to the listener when
 * the playback clock reaches its timestamp. Listener callbacks come from these threads.
 */
public final class VideoPlayer implements AutoCloseable {

    public interface Listener {
        void onFrame(VideoFrame frame);

        default void onEnd() {
        }

        default void onError(Exception e) {
        }
    }

    /**
     * Decoded pictures kept ahead of the display, as a duration: files store ~0.5 s of pictures
     * between two audio chunks, and the decoder must get past them to feed the sound.
     */
    private static final long MAX_QUEUED_MICROS = 1_000_000;
    /** Screens refresh at 60 Hz: closer pictures (e.g. 120 fps videos) are not queued for playback. */
    private static final long MIN_PICTURE_INTERVAL_MICROS = 15_000;
    private static final long POLL_MILLIS = 20;

    private sealed interface Command {
    }

    private record Play() implements Command {
    }

    private record Pause() implements Command {
    }

    private record Seek(long micros) implements Command {
    }

    private record Step(boolean forward) implements Command {
    }

    private record EndReached(int generation) implements Command {
    }

    private record Close() implements Command {
    }

    /** A decoded picture tagged with the generation it belongs to; seeking starts a new generation. */
    private record Queued(VideoFrame frame, int generation) {
    }

    private final MediaDecoder media;
    private final Listener listener;
    private final AudioOutput audio;
    private final PlaybackClock clock;
    private final long durationMicros;
    private final double frameRate;
    private final int rotation;

    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final BlockingQueue<Queued> pictures = new LinkedBlockingQueue<>();
    private final Object presentLock = new Object();
    private final Thread decoder;
    private final Thread presenter;

    // Shared between threads.
    private volatile boolean playing;
    private volatile boolean decodedToEnd;
    private volatile boolean closed;
    private volatile long position;
    private int generation; // guarded by presentLock

    // Decoder thread only.
    /** True while the media has been decoded beyond the displayed picture (during or after playback). */
    private boolean decodedAhead;
    /** True when audio packets were skipped (frame stepping), so playback must re-seek first. */
    private boolean audioSkipped;
    private boolean ended;
    private Queued pendingPicture;
    private long lastQueuedTimestamp = Long.MIN_VALUE;
    private byte[] pendingAudio;
    private int pendingAudioOffset;
    /** The first sound decoded after repositioning tells the clock where the audio really starts. */
    private boolean audioAnchorPending;

    private VideoPlayer(MediaDecoder media, Listener listener, AudioOutput audio) {
        this.media = media;
        this.listener = listener;
        this.audio = audio;
        this.clock = new PlaybackClock(audio != null ? audio::playedMicros : null);
        this.durationMicros = media.durationMicros();
        this.frameRate = media.frameRate();
        this.rotation = Rotation.clockwiseDegrees(media.displayRotation());
        this.decoder = new Thread(this::decodeLoop, "video-decoder");
        this.presenter = new Thread(this::presentLoop, "video-presenter");
        decoder.setDaemon(true);
        presenter.setDaemon(true);
    }

    /**
     * Opens a video, paused on its first frame. Displayed frames are scaled down so that their
     * largest side is at most {@code maxDisplaySize}.
     */
    public static VideoPlayer open(Path file, int maxDisplaySize, Listener listener) throws Exception {
        MediaDecoder media = MediaDecoder.open(file, maxDisplaySize);
        try {
            AudioOutput audio = media.hasAudio() ? AudioOutput.open(media.sampleRate(), media.audioChannels()) : null;
            VideoPlayer player = new VideoPlayer(media, listener, audio);
            player.commands.add(new Seek(0));
            player.decoder.start();
            player.presenter.start();
            return player;
        } catch (RuntimeException e) {
            media.close();
            throw e;
        }
    }

    public void play() {
        commands.add(new Play());
    }

    public void pause() {
        commands.add(new Pause());
    }

    public void seek(long micros) {
        commands.add(new Seek(micros));
    }

    /** Pauses if needed and shows the next frame. */
    public void stepForward() {
        commands.add(new Step(true));
    }

    /** Pauses if needed and shows the previous frame. */
    public void stepBackward() {
        commands.add(new Step(false));
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Timestamp of the frame currently displayed. */
    public long positionMicros() {
        return position;
    }

    public long durationMicros() {
        return durationMicros;
    }

    public double frameRate() {
        return frameRate;
    }

    /** True when the pictures are decoded by the Mac's media engine (known after the first picture). */
    public boolean hardwareAccelerated() {
        return media.hardwareAccelerated();
    }

    /** Clockwise rotation (0, 90, 180, 270) to apply to the frames to display them upright. */
    public int rotation() {
        return rotation;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        commands.add(new Close());
        try {
            decoder.join(2000);
            presenter.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- decoder thread -------------------------------------------------------------------

    private void decodeLoop() {
        try {
            while (true) {
                Command command = playing ? commands.poll() : commands.take();
                if (command instanceof Close) {
                    return;
                }
                // While the slider is dragged, only the latest of the queued seeks matters.
                if (command instanceof Seek && commands.peek() instanceof Seek) {
                    continue;
                }
                if (command != null) {
                    handle(command);
                } else {
                    try {
                        decodeNext();
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        stopPlaying();
                        listener.onError(e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
            if (audio != null) {
                audio.close();
            }
            media.close();
        }
    }

    private void handle(Command command) {
        try {
            switch (command) {
                case Play p -> startPlaying();
                case Pause p -> stopPlaying();
                case Seek s -> {
                    boolean wasPlaying = playing;
                    stopPlaying();
                    showFrameAt(s.micros());
                    if (wasPlaying) {
                        startPlaying();
                    }
                }
                case Step s -> {
                    stopPlaying();
                    if (s.forward()) {
                        showNextFrame();
                    } else {
                        showPreviousFrame();
                    }
                }
                case EndReached e -> {
                    if (playing && e.generation() == currentGeneration()) {
                        stopPlaying();
                        ended = true;
                        listener.onEnd();
                    }
                }
                case Close c -> {
                }
            }
        } catch (Exception e) {
            stopPlaying();
            listener.onError(e);
        }
    }

    private void startPlaying() throws Exception {
        if (playing) {
            return;
        }
        if (ended) {
            showFrameAt(0);
        } else if (!decodedAhead && audioSkipped) {
            resync();
        }
        if (decodedAhead) {
            clock.resume();
        } else {
            clock.reset(position, true);
            decodedAhead = true;
        }
        if (audio != null) {
            audio.start();
        }
        playing = true;
    }

    private void stopPlaying() {
        if (!playing) {
            return;
        }
        playing = false;
        if (audio != null) {
            audio.stop();
        }
        clock.pause();
    }

    /** Drops what was decoded ahead so that the decoder can be repositioned. */
    private void discardDecodedAhead() {
        synchronized (presentLock) {
            generation++;
        }
        pictures.clear();
        pendingPicture = null;
        lastQueuedTimestamp = Long.MIN_VALUE;
        pendingAudio = null;
        if (audio != null) {
            audio.flush();
        }
        decodedToEnd = false;
        clock.setAudioExhausted(false);
        decodedAhead = false;
        ended = false;
        audioAnchorPending = true;
    }

    private void showFrameAt(long micros) throws Exception {
        discardDecodedAhead();
        long target = Math.max(0, Math.min(micros, durationMicros));
        media.seek(target);
        VideoFrame frame = media.nextPicture();
        if (frame == null && target > 0) {
            // Past the last frame: fall back to one frame earlier.
            media.seek(Math.max(0, target - frameDurationMicros()));
            frame = media.nextPicture();
        }
        audioSkipped = false;
        if (frame != null) {
            presentNow(frame);
        }
    }

    private void showNextFrame() throws Exception {
        if (decodedAhead || audioSkipped) {
            resync();
        }
        discardDecodedAhead();
        VideoFrame frame = media.nextPicture();
        audioSkipped = true;
        if (frame == null) {
            showFrameAt(position);
            return;
        }
        presentNow(frame);
    }

    private void showPreviousFrame() throws Exception {
        long current = position;
        long duration = frameDurationMicros();
        // Aim at the middle of the previous frame; widen the jump for variable frame rate videos.
        for (long offset = duration / 2; ; offset += duration) {
            long target = Math.max(0, current - offset);
            showFrameAt(target);
            if (position < current || target == 0) {
                return;
            }
        }
    }

    /** Positions the decoder right after the displayed frame, with audio aligned. */
    private void resync() throws Exception {
        discardDecodedAhead();
        media.seek(position);
        media.nextPicture();
        audioSkipped = false;
    }

    private void decodeNext() throws Exception {
        if (pendingPicture != null) {
            Queued oldest = pictures.peek();
            if (oldest != null && pendingPicture.frame().timestamp() - oldest.frame().timestamp() > MAX_QUEUED_MICROS) {
                Thread.sleep(POLL_MILLIS);
            } else {
                pictures.add(pendingPicture);
                lastQueuedTimestamp = pendingPicture.frame().timestamp();
                pendingPicture = null;
            }
            return;
        }
        if (pendingAudio != null) {
            int written = audio.write(pendingAudio, pendingAudioOffset, pendingAudio.length - pendingAudioOffset);
            pendingAudioOffset += written;
            if (pendingAudioOffset >= pendingAudio.length) {
                pendingAudio = null;
            } else if (written == 0) {
                Thread.sleep(5);
            }
            return;
        }
        if (decodedToEnd) {
            Thread.sleep(POLL_MILLIS);
            return;
        }
        long minPictureTimestamp = lastQueuedTimestamp == Long.MIN_VALUE
                ? Long.MIN_VALUE : lastQueuedTimestamp + MIN_PICTURE_INTERVAL_MICROS;
        Decoded decoded = media.next(audio != null, minPictureTimestamp);
        if (decoded == null) {
            decodedToEnd = true;
            clock.setAudioExhausted(true);
        } else if (decoded instanceof Picture p) {
            pendingPicture = new Queued(p.frame(), currentGeneration());
        } else if (decoded instanceof Sound sound) {
            if (audioAnchorPending) {
                clock.anchorAudio(sound.timestamp());
                audioAnchorPending = false;
            }
            pendingAudio = sound.pcm();
            pendingAudioOffset = 0;
        }
    }

    private long frameDurationMicros() {
        return Math.round(1_000_000 / frameRate);
    }

    private int currentGeneration() {
        synchronized (presentLock) {
            return generation;
        }
    }

    private void presentNow(VideoFrame frame) {
        synchronized (presentLock) {
            position = frame.timestamp();
            listener.onFrame(frame);
        }
    }

    // ---- presenter thread -----------------------------------------------------------------

    private void presentLoop() {
        try {
            while (!closed) {
                Queued next = pictures.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (next == null) {
                    if (playing && decodedToEnd && pendingPicture == null) {
                        commands.add(new EndReached(currentGeneration()));
                        Thread.sleep(POLL_MILLIS);
                    }
                    continue;
                }
                if (waitUntilDue(next)) {
                    presentIfCurrent(next);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns false if the picture became obsolete or is too late to be worth showing. */
    private boolean waitUntilDue(Queued picture) throws InterruptedException {
        long due = picture.frame().timestamp();
        while (!closed && picture.generation() == currentGeneration()) {
            long wait = due - clock.now();
            if (wait <= 0) {
                boolean late = -wait > 2 * frameDurationMicros() && !pictures.isEmpty();
                return !late;
            }
            Thread.sleep(Math.max(1, Math.min(10, wait / 1000)));
        }
        return false;
    }

    private void presentIfCurrent(Queued picture) {
        synchronized (presentLock) {
            if (picture.generation() == generation) {
                position = picture.frame().timestamp();
                listener.onFrame(picture.frame());
            }
        }
    }
}
