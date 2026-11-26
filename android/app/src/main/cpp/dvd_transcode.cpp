#include <jni.h>
#include "dvd_codecs.h"
#include <exception>
#include <memory>
#include <vector>

// The audio/SPU JNI entry points also run in host-side mux integration tests.
#ifdef __ANDROID__
#include <media/NdkMediaCodec.h>
#include <dlfcn.h>
#include <chrono>
#include <cstdint>
#include <deque>
#include "dvd_yuv.h"

extern "C" {
#include "libmpeg2/mpeg2.h"
}

namespace {
using dvd::I420;
using dvd::NV12;

struct Session {
    mpeg2dec_t *decoder = mpeg2_init();
    AMediaCodec *encoder = nullptr;
    int width = 0, height = 0, color = 0, stride = 0, sliceHeight = 0;
    bool ended = false;
    // Half-period units of the MPEG 27 MHz clock retain fractional 90 kHz ticks.
    int64_t nextClock = 0;
    // Baseline AVC has no B pictures. Match each access unit to its source
    // display PTS even on encoders that rebase output timestamps at startup.
    std::deque<int64_t> pendingPts90k;
    using SetParameters = media_status_t (*)(AMediaCodec *, const AMediaFormat *);
    SetParameters setParameters = reinterpret_cast<SetParameters>(dlsym(RTLD_DEFAULT, "AMediaCodec_setParameters"));

    ~Session() {
        if (encoder) { AMediaCodec_stop(encoder); AMediaCodec_delete(encoder); }
        if (decoder) mpeg2_close(decoder);
    }

    bool drain(JNIEnv *env, jobject mux, jmethodID emit, int64_t timeout) {
        auto deliver = [&](const uint8_t *bytes, size_t size, int64_t pts, bool csd) {
            auto sample = env->NewByteArray(static_cast<jsize>(size));
            if (!sample) return false;
            env->SetByteArrayRegion(sample, 0, size, reinterpret_cast<const jbyte *>(bytes));
            if (!env->ExceptionCheck())
                env->CallVoidMethod(mux, emit, static_cast<jlong>(pts), sample, static_cast<jboolean>(csd));
            env->DeleteLocalRef(sample);
            return !env->ExceptionCheck();
        };
        while (!ended) {
            AMediaCodecBufferInfo info{};
            ssize_t index = AMediaCodec_dequeueOutputBuffer(encoder, &info, timeout);
            timeout = 0; // Drain every available access unit before waiting for another input.
            if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat *format = AMediaCodec_getOutputFormat(encoder);
                if (!format) return false;
                bool ok = true;
                for (const char *key : {"csd-0", "csd-1"}) {
                    void *p; size_t n;
                    if (ok && AMediaFormat_getBuffer(format, key, &p, &n) && n)
                        ok = deliver(static_cast<const uint8_t *>(p), n, 0, true);
                }
                AMediaFormat_delete(format);
                if (!ok) return false;
                continue;
            }
            if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (index < 0) return index == AMEDIACODEC_INFO_TRY_AGAIN_LATER;
            size_t capacity;
            auto bytes = AMediaCodec_getOutputBuffer(encoder, index, &capacity);
            bool valid = info.size == 0 || (bytes && info.offset >= 0 && info.size > 0 &&
                static_cast<size_t>(info.offset) + info.size <= capacity);
            if (valid && info.size > 0) {
                bool config = (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (config) {
                    valid = deliver(bytes + info.offset, info.size, 0, true);
                } else if (pendingPts90k.empty()) {
                    valid = false;
                } else {
                    valid = deliver(bytes + info.offset, info.size, pendingPts90k.front(), false);
                    pendingPts90k.pop_front();
                }
            }
            ended = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            AMediaCodec_releaseOutputBuffer(encoder, index, false);
            if (!valid || env->ExceptionCheck()) return false;
        }
        return true;
    }

    bool queuePlanes(JNIEnv *env, jobject mux, jmethodID emit,
                     const mpeg2_fbuf_t *frame, const mpeg2_sequence_t *seq, int64_t pts90k, bool blend = false) {
        using namespace std::chrono;
        const auto deadline = steady_clock::now() + seconds(10);
        while (steady_clock::now() < deadline) {
            if (!drain(env, mux, emit, 0)) return false;
            ssize_t index = AMediaCodec_dequeueInputBuffer(encoder, 10000);
            if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) continue;
            if (index < 0) return false;
            if (!frame) {
                return AMediaCodec_queueInputBuffer(encoder, index, 0, 0, nextClock / 54,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) == AMEDIA_OK;
            }
            size_t capacity;
            auto dst = AMediaCodec_getInputBuffer(encoder, index, &capacity);
            const auto size = dvd::pack420(dst, capacity, color, stride, sliceHeight, width, height,
                                           frame->buf[0], seq->width, frame->buf[1], frame->buf[2], seq->chroma_width, blend);
            if (!size) return false;
            if (AMediaCodec_queueInputBuffer(encoder, index, 0, size, pts90k * 1000 / 90, 0) != AMEDIA_OK) return false;
            pendingPts90k.push_back(pts90k);
            return true;
        }
        return false;
    }
};

} // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_com_gdstream_DvdProxy_hasDecoder(JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_gdstream_DvdProxy_open(JNIEnv *env, jobject, jstring name, jint color,
                                                                  jint w, jint h, jdouble fps, jlong basePts90k) {
    if (w < 1 || w > 720 || h < 1 || h > 576 || !(fps >= 1 && fps <= 60)) return 0;
    auto s = std::make_unique<Session>();
    if (!s->decoder || ((w | h) & 1) || (color != I420 && color != NV12)) return 0;

    const char *encoder = env->GetStringUTFChars(name, nullptr);
    if (!encoder) return 0;
    s->encoder = AMediaCodec_createCodecByName(encoder);
    env->ReleaseStringUTFChars(name, encoder);
    if (!s->encoder) return 0;

    auto format = AMediaFormat_new();
    if (!format) return 0;
    AMediaFormat_setString(format, "mime", "video/avc");
    AMediaFormat_setInt32(format, "width", w);
    AMediaFormat_setInt32(format, "height", h);
    AMediaFormat_setInt32(format, "color-format", color);
    AMediaFormat_setInt32(format, "stride", w);
    AMediaFormat_setInt32(format, "slice-height", h);
    AMediaFormat_setInt32(format, "bitrate", 4500000);
    AMediaFormat_setFloat(format, "frame-rate", static_cast<float>(fps));
    AMediaFormat_setInt32(format, "max-bframes", 0);
    AMediaFormat_setInt32(format, "latency", 0);
    AMediaFormat_setInt32(format, "i-frame-interval", s->setParameters ? 1 : 0);
    AMediaFormat_setInt32(format, "profile", 1); // AVC baseline: zero-latency, no B-frame reorder
    auto status = AMediaCodec_configure(s->encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK || AMediaCodec_start(s->encoder) != AMEDIA_OK) return 0;
    s->color = color;
    s->stride = w;
    s->sliceHeight = h;
    // API 24-27 do not export getInputFormat. Their byte-buffer encoders use the configured layout.
    using GetInputFormat = AMediaFormat *(*)(AMediaCodec *);
    if (auto getInputFormat = reinterpret_cast<GetInputFormat>(dlsym(RTLD_DEFAULT, "AMediaCodec_getInputFormat"))) {
        AMediaFormat *inputFormat = getInputFormat(s->encoder);
        if (inputFormat) {
            int32_t value;
            if (AMediaFormat_getInt32(inputFormat, "stride", &value) && value > 0) s->stride = value;
            if (AMediaFormat_getInt32(inputFormat, "slice-height", &value) && value > 0) s->sliceHeight = value;
            if (AMediaFormat_getInt32(inputFormat, "color-format", &value)) s->color = value;
            AMediaFormat_delete(inputFormat);
        }
    }
    if ((s->color != I420 && s->color != NV12) || s->stride < w || s->sliceHeight < h) return 0;
    if (s->stride > 4096 || s->sliceHeight > 4096 || (s->stride | s->sliceHeight) & 1) return 0;
    s->width = w; s->height = h;
    s->nextClock = basePts90k * 600;
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT jlong JNICALL Java_com_gdstream_DvdProxy_encode(JNIEnv *env, jobject, jlong handle,
                                                                    jbyteArray input, jobject mux, jboolean boundary, jboolean last) {
    auto &s = *reinterpret_cast<Session *>(handle);
    auto emit = env->GetMethodID(env->GetObjectClass(mux), "video", "(J[BZ)V");
    if (!emit) return -1;

    auto run = [&]() {
        // A segment may stop at a VOBU boundary without an MPEG sequence end.
        // Sending that start code completes the last slice and releases the delayed reference frame.
        std::vector<uint8_t> bytes;
        if (input) {
            bytes.resize(env->GetArrayLength(input));
            if (bytes.empty()) return true;
            env->GetByteArrayRegion(input, 0, bytes.size(), reinterpret_cast<jbyte *>(bytes.data()));
            if (env->ExceptionCheck()) return false;
        } else if (boundary || last) {
            bytes = {0, 0, 1, 0xb7};
        }
        if (!bytes.empty()) mpeg2_buffer(s.decoder, bytes.data(), bytes.data() + bytes.size());
        const auto *info = mpeg2_info(s.decoder);
        while (!bytes.empty()) {
            auto state = mpeg2_parse(s.decoder);
            if (state == STATE_BUFFER) break;
            // A VOBU can start partway through a GOP. libmpeg2 resynchronizes on its next
            // start code after STATE_INVALID, and the mux rejects a segment with no frames.
            const auto *seq = info->sequence;
            if (state == STATE_SEQUENCE || state == STATE_SEQUENCE_REPEATED || state == STATE_SEQUENCE_MODIFIED) {
                if (!seq || seq->picture_width != s.width || seq->picture_height != s.height ||
                    seq->chroma_width * 2 != seq->width || seq->chroma_height * 2 != seq->height) return false;
            }
            const auto *picture = info->display_picture;
            const auto *frame = info->display_fbuf;
            if (!input && !last && state == STATE_END) mpeg2_resume_vobu(s.decoder);
            const auto fields = mpeg2_take_display_fields(s.decoder, state);
            if (!fields || !seq) continue;
            // A sequential VOBU can repeat or reset its PES clock. The first PES
            // anchored this session at open; only displayed fields advance it.
            const int64_t videoPts90k = s.nextClock / 600;
            s.nextClock += static_cast<int64_t>(picture->frame_period) * fields;
            if (!s.queuePlanes(env, mux, emit, frame, seq, videoPts90k,
                               !(picture->flags & PIC_FLAG_PROGRESSIVE_FRAME))) return false;
        }
        if (input) return true;
        if (last && !s.queuePlanes(env, mux, emit, nullptr, nullptr, 0)) return false;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(last ? 10000 : 350);
        while ((last ? !s.ended : !s.pendingPts90k.empty()) && std::chrono::steady_clock::now() < deadline)
            if (!s.drain(env, mux, emit, 10000)) return false;
        if (last) return s.ended && s.pendingPts90k.empty();
        if (!s.pendingPts90k.empty()) return false;
        if (!s.setParameters) return true;
        auto params = AMediaFormat_new();
        if (!params) return false;
        AMediaFormat_setInt32(params, "request-sync", 0);
        const auto status = s.setParameters(s.encoder, params);
        AMediaFormat_delete(params);
        return status == AMEDIA_OK;
    };

    if (!run()) {
        if (!env->ExceptionCheck())
            env->ThrowNew(env->FindClass("java/io/IOException"), "DVD MPEG-2 to AVC conversion failed");
        return -1;
    }
    return s.nextClock / 600; // Exclusive end, including the actual last picture's fields.
}

extern "C" JNIEXPORT void JNICALL Java_com_gdstream_DvdProxy_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}

#endif // __ANDROID__

namespace {
void codecError(JNIEnv *env, const std::exception &error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
jbyteArray toBytes(JNIEnv *env, const std::vector<uint8_t> &bytes) {
    auto out = env->NewByteArray(bytes.size());
    if (out && !bytes.empty()) env->SetByteArrayRegion(out, 0, bytes.size(), reinterpret_cast<const jbyte *>(bytes.data()));
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_gdstream_DvdProxy_openAudio(JNIEnv *env, jobject, jint id) {
    try {
        auto track = std::make_unique<dvd::Audio>();
        track->open(id);
        return reinterpret_cast<jlong>(track.release());
    } catch (const std::exception &e) { codecError(env, e); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_gdstream_DvdProxy_convertAudio(
        JNIEnv *env, jobject, jlong handle, jbyteArray input, jint start, jint size, jboolean last) {
    try {
        std::vector<uint8_t> bytes(size);
        if (size) env->GetByteArrayRegion(input, start, size, reinterpret_cast<jbyte *>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        return toBytes(env, reinterpret_cast<dvd::Audio *>(handle)->convert(bytes.data(), size, last));
    } catch (const std::exception &e) { codecError(env, e); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_gdstream_DvdProxy_closeAudio(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<dvd::Audio *>(handle);
}

extern "C" JNIEXPORT jobject JNICALL Java_com_gdstream_DvdProxy_decodeSpu(
        JNIEnv *env, jobject, jbyteArray input, jintArray palette, jint width, jint height) {
    try {
        int32_t clut[16];
        env->GetIntArrayRegion(palette, 0, 16, clut);
        if (env->ExceptionCheck()) return nullptr;
        std::vector<uint8_t> bytes(env->GetArrayLength(input));
        env->GetByteArrayRegion(input, 0, bytes.size(), reinterpret_cast<jbyte *>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto display = dvd::subpicture(bytes.data(), bytes.size(), clut, width, height);
        if (display.segments.empty()) return nullptr;
        auto type = env->FindClass("com/gdstream/DvdSpu$Display");
        if (!type) return nullptr;
        auto init = env->GetMethodID(type, "<init>", "(JJ[B)V");
        auto segments = toBytes(env, display.segments);
        return init && segments ? env->NewObject(type, init, static_cast<jlong>(display.show),
            static_cast<jlong>(display.stop), segments) : nullptr;
    } catch (const std::exception &e) { codecError(env, e); return nullptr; }
}
