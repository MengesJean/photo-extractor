package fr.photoextractor.video;

import fr.photoextractor.video.StreamSelector.AudioStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamSelectorTest {

    @Test
    void skipsUndecodableSpatialAudioOfRecentIphones() {
        // iPhone 17 Pro: stream 1 = AAC (default), stream 2 = APAC spatial audio (no FFmpeg decoder).
        List<AudioStream> streams = List.of(new AudioStream(1, true, true), new AudioStream(2, false, false));

        assertEquals(1, StreamSelector.chooseAudio(streams));
    }

    @Test
    void prefersTheDefaultDecodableStream() {
        List<AudioStream> streams = List.of(new AudioStream(1, true, false), new AudioStream(2, true, true));

        assertEquals(2, StreamSelector.chooseAudio(streams));
    }

    @Test
    void fallsBackToAnyDecodableStream() {
        List<AudioStream> streams = List.of(new AudioStream(1, false, true), new AudioStream(3, true, false));

        assertEquals(3, StreamSelector.chooseAudio(streams));
    }

    @Test
    void noDecodableAudioMeansNoAudio() {
        assertEquals(StreamSelector.NO_STREAM, StreamSelector.chooseAudio(List.of(new AudioStream(1, false, true))));
        assertEquals(StreamSelector.NO_STREAM, StreamSelector.chooseAudio(List.of()));
    }
}
