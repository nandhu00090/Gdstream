// Jellyfin links FFmpeg into libffmpegJNI.so. Resolve the public API at runtime so
// an audio-only AAR (or an unavailable codec) can still play DVDs through VLC.
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <dlfcn.h>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace {
// Public prefixes of AVFrame (libavutil 58) and AVPacket (libavcodec 60).
// Storage is always allocated and released by the bundled library.
struct Frame {
    uint8_t *planes[8];
    int stride[8];
    uint8_t **extended;
    int width, height, samples, format, key, pictureType, aspectNum, aspectDen;
    int64_t pts;
};
struct Packet { void *buffer; int64_t pts, dts; uint8_t *data; };
struct Context;
struct Codec;

struct FFmpeg {
    void *library = dlopen("libffmpegJNI.so", RTLD_NOW | RTLD_LOCAL);
    template<class T> T symbol(const char *name) { return library ? reinterpret_cast<T>(dlsym(library, name)) : nullptr; }
    const Codec *(*find)(int) = symbol<decltype(find)>("avcodec_find_decoder");
    Context *(*context)(const Codec *) = symbol<decltype(context)>("avcodec_alloc_context3");
    void (*freeContext)(Context **) = symbol<decltype(freeContext)>("avcodec_free_context");
    int (*open)(Context *, const Codec *, void *) = symbol<decltype(open)>("avcodec_open2");
    Packet *(*packet)() = symbol<decltype(packet)>("av_packet_alloc");
    int (*allocate)(Packet *, int) = symbol<decltype(allocate)>("av_new_packet");
    void (*unref)(Packet *) = symbol<decltype(unref)>("av_packet_unref");
    void (*freePacket)(Packet **) = symbol<decltype(freePacket)>("av_packet_free");
    Frame *(*frame)() = symbol<decltype(frame)>("av_frame_alloc");
    void (*freeFrame)(Frame **) = symbol<decltype(freeFrame)>("av_frame_free");
    int (*send)(Context *, const Packet *) = symbol<decltype(send)>("avcodec_send_packet");
    int (*receive)(Context *, Frame *) = symbol<decltype(receive)>("avcodec_receive_frame");
    const Codec *decoder() const {
        constexpr int AV_CODEC_ID_MPEG2VIDEO = 2;
        return find && context && freeContext && open && packet && allocate && unref && freePacket &&
            frame && freeFrame && send && receive ? find(AV_CODEC_ID_MPEG2VIDEO) : nullptr;
    }
};
FFmpeg &api() { static FFmpeg instance; return instance; }

struct Session {
    Context *decoder = nullptr;
    Packet *packet = nullptr;
    Frame *frame = nullptr;
    AMediaCodec *encoder = nullptr;
    int width, height, color;
    bool ended = false;
    int64_t lastPts = 0, frameDuration;
    std::vector<uint8_t> config;
    ~Session() {
        if (encoder) { AMediaCodec_stop(encoder); AMediaCodec_delete(encoder); }
        if (decoder) api().freeContext(&decoder);
        if (frame) api().freeFrame(&frame);
        if (packet) api().freePacket(&packet);
    }

    bool drain(JNIEnv *env, jobject mux, jmethodID emit, int64_t timeout) {
        AMediaCodecBufferInfo info{};
        ssize_t index = AMediaCodec_dequeueOutputBuffer(encoder, &info, timeout);
        if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *format = AMediaCodec_getOutputFormat(encoder);
            config.clear();
            for (const char *key : {"csd-0", "csd-1"}) {
                void *p; size_t n;
                if (AMediaFormat_getBuffer(format, key, &p, &n)) {
                    auto bytes = static_cast<uint8_t *>(p);
                    config.insert(config.end(), bytes, bytes + n);
                }
            }
            AMediaFormat_delete(format);
            return true;
        }
        if (index < 0) return index == AMEDIACODEC_INFO_TRY_AGAIN_LATER || index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED;
        size_t capacity;
        auto bytes = AMediaCodec_getOutputBuffer(encoder, index, &capacity);
        bool valid = info.size == 0 || (bytes && info.offset >= 0 && info.size > 0 &&
            static_cast<size_t>(info.offset) + info.size <= capacity);
        if (valid && info.size > 0) {
            bytes += info.offset;
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) config.assign(bytes, bytes + info.size);
            else {
                const size_t prefix = (info.flags & 1) ? config.size() : 0;
                auto sample = env->NewByteArray(static_cast<jsize>(prefix + info.size));
                if (sample) {
                    if (prefix) env->SetByteArrayRegion(sample, 0, prefix, reinterpret_cast<const jbyte *>(config.data()));
                    env->SetByteArrayRegion(sample, prefix, info.size, reinterpret_cast<const jbyte *>(bytes));
                    env->CallVoidMethod(mux, emit, static_cast<jlong>(info.presentationTimeUs), sample);
                    env->DeleteLocalRef(sample);
                }
            }
        }
        ended = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        AMediaCodec_releaseOutputBuffer(encoder, index, false);
        return valid && !env->ExceptionCheck();
    }

    bool queue(JNIEnv *env, jobject mux, jmethodID emit, bool eos) {
        using namespace std::chrono;
        const auto deadline = steady_clock::now() + seconds(10);
        while (steady_clock::now() < deadline) {
            if (!drain(env, mux, emit, 0)) return false;
            ssize_t index = AMediaCodec_dequeueInputBuffer(encoder, 10000);
            if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) continue;
            if (index < 0) return false;
            if (eos) return AMediaCodec_queueInputBuffer(encoder, index, 0, 0, lastPts + frameDuration,
                AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) == AMEDIA_OK;
            size_t capacity;
            auto dst = AMediaCodec_getInputBuffer(encoder, index, &capacity);
            int pixels = width * height;
            if (!dst || capacity < static_cast<size_t>(pixels * 3 / 2) || frame->format != 0 ||
                frame->width != width || frame->height != height) return false;
            for (int y = 0; y < height; ++y)
                memcpy(dst + y * width, frame->planes[0] + y * frame->stride[0], width);
            for (int plane = 1; plane <= 2; ++plane) {
                for (int y = 0; y < height / 2; ++y) {
                    const auto src = frame->planes[plane] + y * frame->stride[plane];
                    if (color == 19) memcpy(dst + pixels + (plane - 1) * pixels / 4 + y * width / 2, src, width / 2);
                    else for (int x = 0; x < width / 2; ++x) dst[pixels + y * width + 2 * x + plane - 1] = src[x];
                }
            }
            lastPts = frame->pts >= 0 ? frame->pts : lastPts + frameDuration;
            return AMediaCodec_queueInputBuffer(encoder, index, 0, pixels * 3 / 2, lastPts, 0) == AMEDIA_OK;
        }
        return false;
    }
};
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_gdstream_DvdProxy_hasDecoder(JNIEnv *, jobject) {
    return api().decoder() != nullptr;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_gdstream_DvdProxy_open(JNIEnv *env, jobject, jstring name, jint color,
                                                                  jint w, jint h, jdouble fps) {
    auto codec = api().decoder();
    if (!codec || w < 1 || w > 720 || h < 1 || h > 576 || fps < 1 || fps > 60) return 0;
    auto s = std::make_unique<Session>();
    s->decoder = api().context(codec);
    s->frame = api().frame();
    s->packet = api().packet();
    if (!s->decoder || !s->frame || !s->packet || api().open(s->decoder, codec, nullptr) < 0) return 0;
    const char *encoder = env->GetStringUTFChars(name, nullptr);
    if (!encoder) return 0;
    s->encoder = AMediaCodec_createCodecByName(encoder);
    env->ReleaseStringUTFChars(name, encoder);
    if (!s->encoder) return 0;
    auto format = AMediaFormat_new();
    AMediaFormat_setString(format, "mime", "video/avc");
    AMediaFormat_setInt32(format, "width", w);
    AMediaFormat_setInt32(format, "height", h);
    AMediaFormat_setInt32(format, "color-format", color);
    AMediaFormat_setInt32(format, "bitrate", 5000000);
    AMediaFormat_setFloat(format, "frame-rate", fps);
    AMediaFormat_setInt32(format, "i-frame-interval", 1);
    AMediaFormat_setInt32(format, "profile", 1); // AVC baseline: no reordered output
    auto status = AMediaCodec_configure(s->encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK || AMediaCodec_start(s->encoder) != AMEDIA_OK) return 0;
    s->width = w; s->height = h; s->color = color; s->frameDuration = 1000000 / fps;
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT void JNICALL Java_com_gdstream_DvdProxy_encode(JNIEnv *env, jobject, jlong handle,
                                                                    jbyteArray input, jlong pts, jobject mux) {
    auto &s = *reinterpret_cast<Session *>(handle);
    auto emit = env->GetMethodID(env->GetObjectClass(mux), "video", "(J[B)V");
    if (!emit) return;
    auto run = [&]() {
        if (input) {
            int length = env->GetArrayLength(input);
            api().unref(s.packet);
            if (api().allocate(s.packet, length) < 0) return false;
            env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(s.packet->data));
            s.packet->pts = pts;
        }
        if (api().send(s.decoder, input ? s.packet : nullptr) < 0) return false;
        int status;
        while ((status = api().receive(s.decoder, s.frame)) == 0)
            if (!s.queue(env, mux, emit, false)) return false;
        if (input) return status == -EAGAIN;
        constexpr int AVERROR_EOF = -541478725;
        if (status != AVERROR_EOF || !s.queue(env, mux, emit, true)) return false;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        while (!s.ended && std::chrono::steady_clock::now() < deadline)
            if (!s.drain(env, mux, emit, 10000)) return false;
        return s.ended;
    };
    if (!run() && !env->ExceptionCheck())
        env->ThrowNew(env->FindClass("java/io/IOException"), "DVD MPEG-2 to AVC conversion failed");
}

extern "C" JNIEXPORT void JNICALL Java_com_gdstream_DvdProxy_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
