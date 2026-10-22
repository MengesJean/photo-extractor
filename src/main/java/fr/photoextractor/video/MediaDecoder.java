package fr.photoextractor.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_DATA_DISPLAYMATRIX;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_clone;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_side_data_get;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD;
import static org.bytedeco.ffmpeg.global.avformat.av_find_best_stream;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_seek_file;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_VIDEOTOOLBOX;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_ref;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_display_rotation_get;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_move_ref;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create;
import static org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert;
import static org.bytedeco.ffmpeg.global.swresample.swr_free;
import static org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

/**
 * Decodes a video file for playback with the FFmpeg API: pictures as BGRA scaled for display,
 * sound as signed 16-bit interleaved PCM (mono or stereo). H.264 and HEVC pictures are decoded by
 * the Mac's media engine (VideoToolbox), which software decoding cannot match for 4K at 120 fps.
 *
 * <p>Timestamps are in microseconds from the start of the video stream, like the ones expected by
 * {@link org.bytedeco.javacv.FFmpegFrameGrabber#setVideoTimestamp(long)}. Not thread-safe.
 */
final class MediaDecoder implements AutoCloseable {

    sealed interface Decoded permits Picture, Sound {
    }

    record Picture(VideoFrame frame) implements Decoded {
    }

    record Sound(long timestamp, byte[] pcm) implements Decoded {
    }

    /** AVERROR(EAGAIN) on macOS, where EAGAIN is 35. */
    private static final int AVERROR_EAGAIN = -35;
    private static final AVRational MICROSECONDS = new AVRational().num(1).den(1_000_000);
    private static final int ROW_ALIGNMENT = 64;

    private final AVFormatContext format;
    private final AVStream videoStream;
    private final AVCodecContext video;
    private final AVStream audioStream;
    private final AVCodecContext audio;
    private final AVBufferRef hardwareDevice;
    private final long startMicros;
    private final int displayWidth;
    private final int displayHeight;
    private final int outputChannels;

    private final AVPacket packet = av_packet_alloc();
    private final AVFrame frame = av_frame_alloc();
    private final AVFrame transferred = av_frame_alloc();
    /** Pictures decoded while seeking, to return before decoding further. */
    private final Deque<AVFrame> pendingPictures = new ArrayDeque<>();
    /** Sound packets read while seeking that belong after the target, to decode before reading further. */
    private final Deque<AVPacket> pendingSound = new ArrayDeque<>();

    private SwsContext scaler;
    private final BytePointer bgra;
    private final int bgraStride;
    private SwrContext resampler;
    private BytePointer pcm = new BytePointer(0L);
    /** After a seek, sound that ends before the target is dropped. */
    private long soundNotBefore = Long.MIN_VALUE;
    private boolean endOfFile;
    private boolean videoDrained;
    private boolean audioDrained;
    private boolean hardwareUsed;
    private boolean closed;

    private MediaDecoder(AVFormatContext format, AVStream videoStream, AVCodecContext video, AVStream audioStream,
                         AVCodecContext audio, AVBufferRef hardwareDevice, int maxDisplaySize) {
        this.format = format;
        this.videoStream = videoStream;
        this.video = video;
        this.audioStream = audioStream;
        this.audio = audio;
        this.hardwareDevice = hardwareDevice;
        this.startMicros = videoStream.start_time() != AV_NOPTS_VALUE
                ? av_rescale_q(videoStream.start_time(), videoStream.time_base(), MICROSECONDS) : 0;
        int w = video.width(), h = video.height();
        double scale = Math.min(1.0, (double) maxDisplaySize / Math.max(w, h));
        this.displayWidth = Math.max(2, (int) Math.round(w * scale) & ~1);
        this.displayHeight = Math.max(2, (int) Math.round(h * scale) & ~1);
        this.bgraStride = (displayWidth * 4 + ROW_ALIGNMENT - 1) / ROW_ALIGNMENT * ROW_ALIGNMENT;
        this.bgra = new BytePointer((long) bgraStride * displayHeight);
        this.outputChannels = audio != null ? Math.min(2, audio.ch_layout().nb_channels()) : 0;
    }

    static MediaDecoder open(Path file, int maxDisplaySize) throws IOException {
        AVFormatContext format = new AVFormatContext(null);
        check(avformat_open_input(format, file.toString(), null, null), "ouverture du fichier");
        AVCodecContext video = null, audio = null;
        AVBufferRef device = null;
        try {
            check(avformat_find_stream_info(format, (AVDictionary) null), "lecture des pistes");
            int videoIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, (AVCodec) null, 0);
            if (videoIndex < 0) {
                throw new IOException("Ce fichier ne contient pas de vidéo");
            }
            AVStream videoStream = format.streams(videoIndex);
            int codecId = videoStream.codecpar().codec_id();
            if (codecId == AV_CODEC_ID_H264 || codecId == AV_CODEC_ID_HEVC) {
                device = createHardwareDevice();
            }
            video = openDecoder(videoStream, device);

            int audioIndex = StreamSelector.audioStreamIndex(format);
            AVStream audioStream = audioIndex >= 0 ? format.streams(audioIndex) : null;
            audio = audioStream != null ? openDecoder(audioStream, null) : null;
            return new MediaDecoder(format, videoStream, video, audioStream, audio, device, maxDisplaySize);
        } catch (IOException | RuntimeException e) {
            free(video);
            free(audio);
            free(device);
            avformat_close_input(format);
            throw e;
        }
    }

    boolean hasAudio() {
        return audio != null;
    }

    int sampleRate() {
        return audio != null ? audio.sample_rate() : 0;
    }

    /** Channels of the PCM returned in {@link Sound}: 1 or 2. */
    int audioChannels() {
        return outputChannels;
    }

    long durationMicros() {
        return format.duration() != AV_NOPTS_VALUE
                ? format.duration()
                : av_rescale_q(videoStream.duration(), videoStream.time_base(), MICROSECONDS);
    }

    double frameRate() {
        AVRational r = videoStream.avg_frame_rate();
        if (r.num() <= 0 || r.den() <= 0) {
            r = videoStream.r_frame_rate();
        }
        return r.num() > 0 && r.den() > 0 ? (double) r.num() / r.den() : 30;
    }

    /** Counter-clockwise angle of the display matrix, as FFmpegFrameGrabber#getDisplayRotation. */
    double displayRotation() {
        AVCodecParameters par = videoStream.codecpar();
        AVPacketSideData matrix = av_packet_side_data_get(par.coded_side_data(), par.nb_coded_side_data(),
                AV_PKT_DATA_DISPLAYMATRIX);
        return matrix != null && !matrix.isNull()
                ? av_display_rotation_get(new IntPointer(matrix.data())) : 0;
    }

    /** True once a picture has been decoded by VideoToolbox. */
    boolean hardwareAccelerated() {
        return hardwareUsed;
    }

    /**
     * Returns the next picture or sound in file order, or null at the end. Pictures earlier than
     * {@code minPictureTimestamp} are decoded but not converted nor returned.
     */
    Decoded next(boolean withAudio, long minPictureTimestamp) throws IOException {
        boolean decodeAudio = withAudio && audio != null;
        while (true) {
            AVFrame held = pendingPictures.poll();
            if (held != null) {
                try {
                    if (timestamp(held, videoStream) >= minPictureTimestamp) {
                        return new Picture(toPicture(held));
                    }
                } finally {
                    av_frame_free(held);
                }
                continue;
            }
            int ret = videoDrained ? AVERROR_EOF : avcodec_receive_frame(video, frame);
            if (ret == 0) {
                try {
                    if (timestamp(frame, videoStream) >= minPictureTimestamp) {
                        return new Picture(toPicture(frame));
                    }
                } finally {
                    av_frame_unref(frame);
                }
                continue;
            }
            videoDrained = ret == AVERROR_EOF;
            checkDecoding(ret);
            if (decodeAudio) {
                int a = audioDrained ? AVERROR_EOF : avcodec_receive_frame(audio, frame);
                if (a == 0) {
                    try {
                        long end = timestamp(frame, audioStream) + 1_000_000L * frame.nb_samples() / frame.sample_rate();
                        if (end > soundNotBefore) {
                            soundNotBefore = Long.MIN_VALUE;
                            return toSound(frame);
                        }
                    } finally {
                        av_frame_unref(frame);
                    }
                    continue;
                }
                audioDrained = a == AVERROR_EOF;
                checkDecoding(a);
                AVPacket stashed = pendingSound.poll();
                if (stashed != null) {
                    avcodec_send_packet(audio, stashed);
                    av_packet_free(stashed);
                    continue;
                }
            }
            if (endOfFile) {
                if (videoDrained && (!decodeAudio || audioDrained)) {
                    return null;
                }
                continue;
            }
            readPacket(decodeAudio, Long.MAX_VALUE);
        }
    }

    /** Returns the next picture, skipping sound, or null at the end. */
    VideoFrame nextPicture() throws IOException {
        Decoded d = next(false, Long.MIN_VALUE);
        return d != null ? ((Picture) d).frame() : null;
    }

    /** Positions the decoder so that the next picture is the one shown at {@code micros}. */
    void seek(long micros) throws IOException {
        long target = micros + startMicros;
        if (avformat_seek_file(format, -1, Long.MIN_VALUE, target, target, AVSEEK_FLAG_BACKWARD) < 0) {
            check(avformat_seek_file(format, -1, Long.MIN_VALUE, target, Long.MAX_VALUE, 0), "déplacement");
        }
        avcodec_flush_buffers(video);
        if (audio != null) {
            avcodec_flush_buffers(audio);
        }
        freeResampler();
        clearPending();
        endOfFile = false;
        videoDrained = false;
        audioDrained = false;
        soundNotBefore = micros;

        // Decode up to the first picture after the target: the one before it is shown at the target.
        AVFrame shown = null;
        while (true) {
            AVFrame f = decodeRawPicture(micros);
            if (f == null) {
                break;
            }
            long ts = timestamp(f, videoStream);
            if (shown != null && ts > micros) {
                pendingPictures.add(shown);
                pendingPictures.add(f);
                return;
            }
            if (shown != null) {
                av_frame_free(shown);
            }
            shown = f;
            if (ts == micros) {
                break;
            }
        }
        if (shown != null) {
            pendingPictures.add(shown);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearPending();
        freeResampler();
        sws_freeContext(scaler);
        av_frame_free(frame);
        av_frame_free(transferred);
        av_packet_free(packet);
        free(video);
        free(audio);
        free(hardwareDevice);
        avformat_close_input(format);
        bgra.close();
        pcm.close();
    }

    // ---- decoding ---------------------------------------------------------------------------

    /** Decodes the next picture without converting it; sound after {@code keepSoundAfter} is stashed. */
    private AVFrame decodeRawPicture(long keepSoundAfter) throws IOException {
        while (true) {
            int ret = videoDrained ? AVERROR_EOF : avcodec_receive_frame(video, frame);
            if (ret == 0) {
                AVFrame owned = av_frame_alloc();
                av_frame_move_ref(owned, frame);
                return owned;
            }
            videoDrained = ret == AVERROR_EOF;
            checkDecoding(ret);
            if (videoDrained) {
                return null;
            }
            if (!endOfFile) {
                readPacket(false, keepSoundAfter);
            }
        }
    }

    /**
     * Reads one packet and sends it to its decoder. Sound packets go to the audio decoder when
     * {@code decodeAudio}, are stashed when they end after {@code keepSoundAfter}, or are dropped.
     */
    private void readPacket(boolean decodeAudio, long keepSoundAfter) throws IOException {
        int ret = av_read_frame(format, packet);
        if (ret == AVERROR_EOF) {
            endOfFile = true;
            avcodec_send_packet(video, null);
            if (audio != null) {
                avcodec_send_packet(audio, null);
            }
            return;
        }
        check(ret, "lecture");
        try {
            if (packet.stream_index() == videoStream.index()) {
                avcodec_send_packet(video, packet);
            } else if (audioStream != null && packet.stream_index() == audioStream.index()) {
                if (decodeAudio) {
                    avcodec_send_packet(audio, packet);
                } else if (keepSoundAfter != Long.MAX_VALUE && soundEnd(packet) > keepSoundAfter) {
                    pendingSound.add(av_packet_clone(packet));
                }
            }
        } finally {
            av_packet_unref(packet);
        }
    }

    private VideoFrame toPicture(AVFrame f) throws IOException {
        AVFrame source = f;
        if (f.format() == AV_PIX_FMT_VIDEOTOOLBOX) {
            av_frame_unref(transferred);
            check(av_hwframe_transfer_data(transferred, f, 0), "transfert de l'image décodée");
            source = transferred;
            hardwareUsed = true;
        }
        scaler = sws_getCachedContext(scaler, source.width(), source.height(), source.format(),
                displayWidth, displayHeight, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, (DoublePointer) null);
        if (scaler == null) {
            throw new IOException("Format d'image non pris en charge : " + source.format());
        }
        try (PointerPointer<?> dst = new PointerPointer<>(1L).put(bgra);
             IntPointer dstStride = new IntPointer(1L).put(bgraStride)) {
            sws_scale(scaler, source.data(), source.linesize(), 0, source.height(), dst, dstStride);
        }
        int rowBytes = displayWidth * 4;
        byte[] pixels = new byte[rowBytes * displayHeight];
        for (int y = 0; y < displayHeight; y++) {
            bgra.position((long) y * bgraStride).get(pixels, y * rowBytes, rowBytes);
        }
        bgra.position(0);
        return new VideoFrame(timestamp(f, videoStream), displayWidth, displayHeight, pixels);
    }

    private Sound toSound(AVFrame f) throws IOException {
        if (resampler == null) {
            AVChannelLayout outLayout = new AVChannelLayout();
            av_channel_layout_default(outLayout, outputChannels);
            SwrContext swr = new SwrContext(null);
            check(swr_alloc_set_opts2(swr, outLayout, AV_SAMPLE_FMT_S16, audio.sample_rate(),
                    f.ch_layout(), f.format(), f.sample_rate(), 0, null), "configuration du son");
            check(swr_init(swr), "configuration du son");
            resampler = swr;
        }
        int capacity = swr_get_out_samples(resampler, f.nb_samples());
        long bytes = (long) capacity * outputChannels * 2;
        if (pcm.capacity() < bytes) {
            pcm.close();
            pcm = new BytePointer(bytes);
        }
        int converted;
        try (PointerPointer<?> out = new PointerPointer<>(1L).put(pcm)) {
            converted = swr_convert(resampler, out, capacity, f.extended_data(), f.nb_samples());
        }
        check(converted, "conversion du son");
        byte[] data = new byte[converted * outputChannels * 2];
        pcm.position(0).get(data);
        return new Sound(timestamp(f, audioStream), data);
    }

    private long timestamp(AVFrame f, AVStream stream) {
        long pts = f.best_effort_timestamp() != AV_NOPTS_VALUE ? f.best_effort_timestamp() : f.pts();
        return pts == AV_NOPTS_VALUE ? 0 : av_rescale_q(pts, stream.time_base(), MICROSECONDS) - startMicros;
    }

    private long soundEnd(AVPacket p) {
        long pts = p.pts() != AV_NOPTS_VALUE ? p.pts() : p.dts();
        return pts == AV_NOPTS_VALUE ? Long.MAX_VALUE
                : av_rescale_q(pts + p.duration(), audioStream.time_base(), MICROSECONDS) - startMicros;
    }

    private void clearPending() {
        for (AVFrame f; (f = pendingPictures.poll()) != null; ) {
            av_frame_free(f);
        }
        for (AVPacket p; (p = pendingSound.poll()) != null; ) {
            av_packet_free(p);
        }
    }

    private void freeResampler() {
        if (resampler != null) {
            swr_free(resampler);
            resampler = null;
        }
    }

    // FFmpeg's free functions take a pointer to the pointer: never hand them a null object.

    private static void free(AVCodecContext context) {
        if (context != null) {
            avcodec_free_context(context);
        }
    }

    private static void free(AVBufferRef buffer) {
        if (buffer != null) {
            av_buffer_unref(buffer);
        }
    }

    private static AVCodecContext openDecoder(AVStream stream, AVBufferRef hardwareDevice) throws IOException {
        AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
        if (codec == null) {
            throw new IOException("Aucun décodeur pour le codec " + stream.codecpar().codec_id());
        }
        AVCodecContext context = avcodec_alloc_context3(codec);
        try {
            check(avcodec_parameters_to_context(context, stream.codecpar()), "préparation du décodeur");
            context.pkt_timebase(stream.time_base());
            context.thread_count(0);
            if (hardwareDevice != null) {
                // FFmpeg then picks the VideoToolbox output format, or falls back to software.
                context.hw_device_ctx(av_buffer_ref(hardwareDevice));
            }
            check(avcodec_open2(context, codec, (AVDictionary) null), "ouverture du décodeur");
            return context;
        } catch (IOException e) {
            avcodec_free_context(context);
            throw e;
        }
    }

    /** Returns null when VideoToolbox is unavailable: decoding then happens in software. */
    private static AVBufferRef createHardwareDevice() {
        AVBufferRef device = new AVBufferRef(null);
        if (av_hwdevice_ctx_create(device, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, (BytePointer) null, null, 0) < 0) {
            return null;
        }
        return device;
    }

    private static void checkDecoding(int ret) throws IOException {
        if (ret < 0 && ret != AVERROR_EAGAIN && ret != AVERROR_EOF) {
            throw new IOException("Erreur de décodage FFmpeg " + ret);
        }
    }

    private static void check(int ret, String step) throws IOException {
        if (ret < 0) {
            throw new IOException("Erreur FFmpeg " + ret + " (" + step + ")");
        }
    }
}
