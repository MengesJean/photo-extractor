package fr.photoextractor.video;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;

/**
 * Picks the audio stream FFmpeg should decode. Recent iPhones store a stereo AAC track plus an
 * Apple spatial audio (APAC) track that FFmpeg cannot decode, and JavaCV's own choice may land
 * on the latter, making the whole file fail to open.
 */
final class StreamSelector {

    static final int NO_STREAM = -1;

    record AudioStream(int index, boolean decodable, boolean isDefault) {
    }

    private StreamSelector() {
    }

    static int chooseAudio(List<AudioStream> streams) {
        return streams.stream()
                .filter(AudioStream::decodable)
                .min(Comparator.comparing((AudioStream s) -> !s.isDefault()))
                .map(AudioStream::index)
                .orElse(NO_STREAM);
    }

    /** Makes the grabber ignore all audio. Call before start(). */
    static void disableAudio(FFmpegFrameGrabber grabber) {
        // No stream has this index, and a non-negative value stops JavaCV from choosing one itself.
        grabber.setAudioStream(Integer.MAX_VALUE);
    }

    /** Index of the audio stream to decode in an opened file, or {@link #NO_STREAM}. */
    static int audioStreamIndex(AVFormatContext context) {
        List<AudioStream> streams = new ArrayList<>();
        for (int i = 0; i < context.nb_streams(); i++) {
            AVStream st = context.streams(i);
            if (st.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                boolean decodable = avcodec_find_decoder(st.codecpar().codec_id()) != null;
                streams.add(new AudioStream(i, decodable, (st.disposition() & AV_DISPOSITION_DEFAULT) != 0));
            }
        }
        return chooseAudio(streams);
    }
}
