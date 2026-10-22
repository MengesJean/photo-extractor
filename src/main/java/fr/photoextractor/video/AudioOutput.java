package fr.photoextractor.video;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Plays signed 16-bit interleaved PCM through Java Sound, without ever blocking the caller. */
final class AudioOutput implements AutoCloseable {

    // Must hold more than one audio chunk of the file: iPhone videos store ~0.5 s of sound per chunk.
    private static final double BUFFER_SECONDS = 1.0;

    private final SourceDataLine line;

    private AudioOutput(SourceDataLine line) {
        this.line = line;
    }

    /** Returns null when no audio device can play this format. */
    static AudioOutput open(int sampleRate, int channels) {
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, (int) (sampleRate * BUFFER_SECONDS) * format.getFrameSize());
            return new AudioOutput(line);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Writes as much as fits in the line buffer right now and returns the number of bytes written. */
    int write(byte[] data, int offset, int length) {
        int frameSize = line.getFormat().getFrameSize();
        int n = Math.min(length, line.available()) / frameSize * frameSize;
        return n > 0 ? line.write(data, offset, n) : 0;
    }

    void start() {
        line.start();
    }

    void stop() {
        line.stop();
    }

    void flush() {
        line.flush();
    }

    long playedMicros() {
        return line.getMicrosecondPosition();
    }

    @Override
    public void close() {
        line.close();
    }
}
