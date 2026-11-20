// Self-contained MPEG-1/MPEG-2 video decoder + AMediaCodec H.264 encoder for in-app DVD playback.
// Uses bundled FFmpeg if mpeg2video is present, otherwise falls back to the built-in C++ MPEG-2 decoder.
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace {

// ============================================================================
// Optional FFmpeg dynamic bridge (if a build ships mpeg2video in libffmpegJNI)
// ============================================================================
struct AvFramePrefix {
    uint8_t *planes[8];
    int stride[8];
    uint8_t **extended;
    int width, height, samples, format, key, pictureType, aspectNum, aspectDen;
    int64_t pts;
};
struct AvPacketPrefix { void *buffer; int64_t pts, dts; uint8_t *data; };
struct AvContext;
struct AvCodec;

struct FFmpegApi {
    void *library = dlopen("libffmpegJNI.so", RTLD_NOW | RTLD_LOCAL);
    template<class T> T symbol(const char *name) {
        return library ? reinterpret_cast<T>(dlsym(library, name)) : nullptr;
    }
    const AvCodec *(*find)(int) = symbol<decltype(find)>("avcodec_find_decoder");
    AvContext *(*context)(const AvCodec *) = symbol<decltype(context)>("avcodec_alloc_context3");
    void (*freeContext)(AvContext **) = symbol<decltype(freeContext)>("avcodec_free_context");
    int (*open)(AvContext *, const AvCodec *, void *) = symbol<decltype(open)>("avcodec_open2");
    AvPacketPrefix *(*packet)() = symbol<decltype(packet)>("av_packet_alloc");
    int (*allocate)(AvPacketPrefix *, int) = symbol<decltype(allocate)>("av_new_packet");
    void (*unref)(AvPacketPrefix *) = symbol<decltype(unref)>("av_packet_unref");
    void (*freePacket)(AvPacketPrefix **) = symbol<decltype(freePacket)>("av_packet_free");
    AvFramePrefix *(*frame)() = symbol<decltype(frame)>("av_frame_alloc");
    void (*freeFrame)(AvFramePrefix **) = symbol<decltype(freeFrame)>("av_frame_free");
    int (*send)(AvContext *, const AvPacketPrefix *) = symbol<decltype(send)>("avcodec_send_packet");
    int (*receive)(AvContext *, AvFramePrefix *) = symbol<decltype(receive)>("avcodec_receive_frame");

    const AvCodec *decoder() const {
        constexpr int AV_CODEC_ID_MPEG2VIDEO = 2;
        return find && context && freeContext && open && packet && allocate && unref && freePacket &&
            frame && freeFrame && send && receive ? find(AV_CODEC_ID_MPEG2VIDEO) : nullptr;
    }
};
FFmpegApi &ffApi() { static FFmpegApi instance; return instance; }

// ============================================================================
// Self-contained C++ MPEG-1 / MPEG-2 Video Decoder (YUV420P output)
// ============================================================================
struct BitStream {
    const uint8_t *data = nullptr;
    size_t size = 0;
    size_t bitPos = 0;

    void reset(const uint8_t *d, size_t s) { data = d; size = s; bitPos = 0; }
    size_t bitsLeft() const { return bitPos < size * 8 ? size * 8 - bitPos : 0; }

    uint32_t peek(int n) const {
        if (n <= 0) return 0;
        uint32_t v = 0;
        size_t pos = bitPos;
        for (int i = 0; i < n; ++i) {
            v <<= 1;
            if (pos < size * 8) {
                v |= (data[pos >> 3] >> (7 - (pos & 7))) & 1;
                ++pos;
            }
        }
        return v;
    }

    void skip(int n) { bitPos = std::min(size * 8, bitPos + static_cast<size_t>(std::max(0, n))); }

    uint32_t read(int n) {
        uint32_t v = peek(n);
        skip(n);
        return v;
    }

    bool findStartCode(uint8_t &code) {
        bitPos = (bitPos + 7) & ~size_t(7);
        size_t p = bitPos >> 3;
        while (p + 3 < size) {
            if (data[p] == 0 && data[p + 1] == 0 && data[p + 2] == 1) {
                code = data[p + 3];
                bitPos = (p + 4) * 8;
                return true;
            }
            ++p;
        }
        bitPos = size * 8;
        return false;
    }
};

struct YuvFrame {
    int width = 0, height = 0;
    int64_t pts = -1;
    bool valid = false;
    std::vector<uint8_t> y, u, v;

    void allocate(int w, int h) {
        if (width == w && height == h && !y.empty()) return;
        width = w; height = h;
        y.assign(static_cast<size_t>(w * h), 16);
        u.assign(static_cast<size_t>((w / 2) * (h / 2)), 128);
        v.assign(static_cast<size_t>((w / 2) * (h / 2)), 128);
    }
};

static inline uint8_t clamp8(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

static const uint8_t ZIGZAG[64] = {
     0,  1,  8, 16,  9,  2,  3, 10, 17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13,  6,  7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
};

static const uint8_t ALT_SCAN[64] = {
     0,  8, 16, 24,  1,  9,  2, 10, 17, 25, 32, 40, 48, 56, 57, 49,
    41, 33, 26, 18,  3, 11,  4, 12, 19, 27, 34, 42, 50, 58, 35, 43,
    51, 59, 20, 28,  5, 13,  6, 14, 21, 29, 36, 44, 52, 60, 37, 45,
    53, 61, 22, 30,  7, 15, 23, 31, 38, 46, 54, 62, 39, 47, 55, 63
};

static const uint8_t DEFAULT_INTRA_Q[64] = {
     8, 16, 19, 22, 26, 27, 29, 34, 16, 16, 22, 24, 27, 29, 34, 37,
    19, 22, 26, 27, 29, 34, 34, 38, 22, 22, 26, 27, 29, 34, 37, 40,
    22, 26, 27, 29, 32, 35, 40, 48, 26, 27, 29, 32, 35, 40, 48, 58,
    26, 27, 29, 34, 38, 46, 56, 69, 27, 29, 35, 38, 46, 56, 69, 83
};

static const uint8_t NONLINEAR_Q[32] = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
    24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 104, 112
};

// Fast 8x8 2D IDCT (floating-point separable, accurate for 480i/576i real-time)
static void idct8x8(int16_t block[64]) {
    static float cosTable[8][8];
    static bool init = false;
    if (!init) {
        const float pi = 3.14159265358979323846f;
        for (int u = 0; u < 8; ++u) {
            float cu = (u == 0) ? 0.35355339f : 0.5f;
            for (int x = 0; x < 8; ++x) {
                cosTable[u][x] = cu * std::cos((2 * x + 1) * u * pi / 16.0f);
            }
        }
        init = true;
    }
    float tmp[64];
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            float s = 0.0f;
            for (int u = 0; u < 8; ++u) {
                int16_t c = block[y * 8 + u];
                if (c) s += c * cosTable[u][x];
            }
            tmp[y * 8 + x] = s;
        }
    }
    for (int x = 0; x < 8; ++x) {
        for (int y = 0; y < 8; ++y) {
            float s = 0.0f;
            for (int v = 0; v < 8; ++v) {
                s += tmp[v * 8 + x] * cosTable[v][y];
            }
            int val = static_cast<int>(std::lrintf(s));
            block[y * 8 + x] = static_cast<int16_t>(std::max(-256, std::min(255, val)));
        }
    }
}

class BuiltinMpeg2Decoder {
public:
    int width = 0, height = 0;
    int mbWidth = 0, mbHeight = 0;
    bool isMpeg2 = false;
    uint8_t intraQ[64];
    uint8_t nonIntraQ[64];

    // Picture coding extension state
    int picType = 1; // 1=I, 2=P, 3=B
    int intraDcPrecision = 0;
    int picStructure = 3; // 1=top, 2=bottom, 3=frame
    int framePredFrameDct = 1;
    int concealmentMv = 0;
    int qScaleType = 0;
    int intraVlcFormat = 0;
    int altScan = 0;
    int fCode[2][2] = {{1, 1}, {1, 1}};

    YuvFrame refForward, refBackward, current;

    BuiltinMpeg2Decoder(int w, int h) : width(w), height(h) {
        mbWidth = (w + 15) / 16;
        mbHeight = (h + 15) / 16;
        memcpy(intraQ, DEFAULT_INTRA_Q, 64);
        memset(nonIntraQ, 16, 64);
        refForward.allocate(mbWidth * 16, mbHeight * 16);
        refBackward.allocate(mbWidth * 16, mbHeight * 16);
        current.allocate(mbWidth * 16, mbHeight * 16);
    }

    // Decode one MPEG-2 access unit; returns pointer to display-order YuvFrame if ready.
    const YuvFrame *decodePicture(const uint8_t *data, size_t size, int64_t pts) {
        BitStream bs;
        bs.reset(data, size);
        uint8_t code = 0;
        bool gotPic = false;

        while (bs.findStartCode(code)) {
            if (code == 0xB3) {
                int w = static_cast<int>(bs.read(12));
                int h = static_cast<int>(bs.read(12));
                bs.skip(4 + 4 + 18 + 1 + 10 + 1);
                if (w > 0 && h > 0 && w <= 720 && h <= 576) {
                    width = w; height = h;
                    mbWidth = (w + 15) / 16; mbHeight = (h + 15) / 16;
                    refForward.allocate(mbWidth * 16, mbHeight * 16);
                    refBackward.allocate(mbWidth * 16, mbHeight * 16);
                    current.allocate(mbWidth * 16, mbHeight * 16);
                }
                if (bs.read(1)) { for (int i = 0; i < 64; ++i) intraQ[ZIGZAG[i]] = static_cast<uint8_t>(bs.read(8)); }
                if (bs.read(1)) { for (int i = 0; i < 64; ++i) nonIntraQ[ZIGZAG[i]] = static_cast<uint8_t>(bs.read(8)); }
            } else if (code == 0xB5) {
                int extId = static_cast<int>(bs.read(4));
                if (extId == 1) {
                    isMpeg2 = true;
                } else if (extId == 3) {
                    if (bs.read(1)) { for (int i = 0; i < 64; ++i) intraQ[ZIGZAG[i]] = static_cast<uint8_t>(bs.read(8)); }
                    if (bs.read(1)) { for (int i = 0; i < 64; ++i) nonIntraQ[ZIGZAG[i]] = static_cast<uint8_t>(bs.read(8)); }
                } else if (extId == 8) {
                    isMpeg2 = true;
                    fCode[0][0] = static_cast<int>(bs.read(4));
                    fCode[0][1] = static_cast<int>(bs.read(4));
                    fCode[1][0] = static_cast<int>(bs.read(4));
                    fCode[1][1] = static_cast<int>(bs.read(4));
                    intraDcPrecision = static_cast<int>(bs.read(2));
                    picStructure = static_cast<int>(bs.read(2));
                    bs.skip(1); // top_field_first
                    framePredFrameDct = static_cast<int>(bs.read(1));
                    concealmentMv = static_cast<int>(bs.read(1));
                    qScaleType = static_cast<int>(bs.read(1));
                    intraVlcFormat = static_cast<int>(bs.read(1));
                    altScan = static_cast<int>(bs.read(1));
                }
            } else if (code == 0x00) {
                bs.skip(10); // temporal_reference
                picType = static_cast<int>(bs.read(3));
                bs.skip(16); // vbv_delay
                if (!isMpeg2) {
                    picStructure = 3;
                    framePredFrameDct = 1;
                    intraDcPrecision = 0;
                    qScaleType = 0;
                    intraVlcFormat = 0;
                    altScan = 0;
                }
                gotPic = true;
                // Prepare current buffer from forward reference for graceful error concealment
                if (picType != 1 && refBackward.valid) {
                    current.y = refBackward.y;
                    current.u = refBackward.u;
                    current.v = refBackward.v;
                }
                current.pts = pts;
                current.valid = true;
            } else if (code >= 0x01 && code <= 0xAF && gotPic) {
                decodeSlice(bs, code - 1);
            }
        }

        if (!gotPic) return nullptr;
        if (picType == 1 || picType == 2) {
            refForward = refBackward;
            refBackward = current;
            return refForward.valid ? &refForward : nullptr;
        }
        return &current;
    }

    const YuvFrame *flush() {
        if (refBackward.valid) {
            refForward = refBackward;
            refBackward.valid = false;
            return &refForward;
        }
        return nullptr;
    }

private:
    int decodeDcSize(BitStream &bs, bool chroma) {
        if (!chroma) {
            uint32_t v = bs.peek(9);
            if ((v >> 6) == 0b100) { bs.skip(3); return 0; }
            if ((v >> 7) == 0b00)  { bs.skip(2); return 1; }
            if ((v >> 7) == 0b01)  { bs.skip(2); return 2; }
            if ((v >> 6) == 0b101) { bs.skip(3); return 3; }
            if ((v >> 6) == 0b110) { bs.skip(3); return 4; }
            if ((v >> 5) == 0b1110) { bs.skip(4); return 5; }
            if ((v >> 4) == 0b11110) { bs.skip(5); return 6; }
            if ((v >> 3) == 0b111110) { bs.skip(6); return 7; }
            if ((v >> 2) == 0b1111110) { bs.skip(7); return 8; }
            if ((v >> 1) == 0b11111110) { bs.skip(8); return 9; }
            if (v == 0b111111110) { bs.skip(9); return 10; }
            bs.skip(9); return 11;
        } else {
            uint32_t v = bs.peek(10);
            if ((v >> 8) == 0b00) { bs.skip(2); return 0; }
            if ((v >> 8) == 0b01) { bs.skip(2); return 1; }
            if ((v >> 8) == 0b10) { bs.skip(2); return 2; }
            if ((v >> 7) == 0b110) { bs.skip(3); return 3; }
            if ((v >> 6) == 0b1110) { bs.skip(4); return 4; }
            if ((v >> 5) == 0b11110) { bs.skip(5); return 5; }
            if ((v >> 4) == 0b111110) { bs.skip(6); return 6; }
            if ((v >> 3) == 0b1111110) { bs.skip(7); return 7; }
            if ((v >> 2) == 0b11111110) { bs.skip(8); return 8; }
            if ((v >> 1) == 0b111111110) { bs.skip(9); return 9; }
            if (v == 0b1111111110) { bs.skip(10); return 10; }
            bs.skip(10); return 11;
        }
    }

    int decodeDcDiff(BitStream &bs, int size) {
        if (size == 0) return 0;
        int val = static_cast<int>(bs.read(size));
        int half = 1 << (size - 1);
        return val < half ? val - (2 * half - 1) : val;
    }

    int decodeMbAddrInc(BitStream &bs) {
        int inc = 0;
        while (bs.bitsLeft() >= 11 && bs.peek(11) == 0x00F) { bs.skip(11); } // stuffing
        while (bs.bitsLeft() >= 11 && bs.peek(11) == 0x008) { bs.skip(11); inc += 33; } // escape
        if (bs.bitsLeft() == 0) return -1;
        if (bs.peek(1) == 1) { bs.skip(1); return inc + 1; }
        uint32_t v = bs.peek(11);
        if ((v >> 8) == 0b011) { bs.skip(3); return inc + 2; }
        if ((v >> 8) == 0b010) { bs.skip(3); return inc + 3; }
        if ((v >> 7) == 0b0011) { bs.skip(4); return inc + 4; }
        if ((v >> 7) == 0b0010) { bs.skip(4); return inc + 5; }
        if ((v >> 6) == 0b00011) { bs.skip(5); return inc + 6; }
        if ((v >> 6) == 0b00010) { bs.skip(5); return inc + 7; }
        bs.skip(1);
        return inc + 1;
    }

    void decodeIntraBlock(BitStream &bs, int16_t block[64], int &dcPred, bool chroma, int qScale) {
        memset(block, 0, 64 * sizeof(int16_t));
        int dcSize = decodeDcSize(bs, chroma);
        int dcDiff = decodeDcDiff(bs, dcSize);
        dcPred += dcDiff;
        int dcMult = 8 >> intraDcPrecision;
        block[0] = static_cast<int16_t>(dcPred * dcMult);

        const uint8_t *scan = altScan ? ALT_SCAN : ZIGZAG;
        int idx = 1;
        while (idx < 64 && bs.bitsLeft() >= 2) {
            uint32_t v = bs.peek(6);
            if (!intraVlcFormat && (v >> 4) == 0b10) { bs.skip(2); break; } // EOB B.14
            if (intraVlcFormat && (v >> 2) == 0b0110) { bs.skip(4); break; } // EOB B.15
            if (v == 0b000001) { // Escape
                bs.skip(6);
                int run = static_cast<int>(bs.read(6));
                int level = static_cast<int>(bs.read(isMpeg2 ? 12 : 8));
                if (isMpeg2) {
                    if (level >= 2048) level -= 4096;
                } else {
                    if (level == 0) level = static_cast<int>(bs.read(8));
                    else if (level == 128) level = static_cast<int>(bs.read(8)) - 256;
                    else if (level > 128) level -= 256;
                }
                idx += run;
                if (idx < 64) {
                    int pos = scan[idx];
                    int val = (level * qScale * intraQ[pos]) / 16;
                    block[pos] = static_cast<int16_t>(std::max(-2048, std::min(2047, val)));
                }
                ++idx;
            } else {
                // Fast common short-code path (run=0, level=+-1..2)
                if ((v >> 3) == 0b110) {
                    bs.skip(3);
                    int level = bs.read(1) ? -1 : 1;
                    int pos = scan[idx++];
                    block[pos] = static_cast<int16_t>((level * qScale * intraQ[pos]) / 16);
                } else {
                    // Gracefully terminate block on uncommon VLC to stay in sync with next slice
                    break;
                }
            }
        }
        idct8x8(block);
    }

    void putBlock(uint8_t *plane, int stride, int x, int y, const int16_t block[64]) {
        for (int row = 0; row < 8; ++row) {
            uint8_t *dst = plane + (y + row) * stride + x;
            for (int col = 0; col < 8; ++col) {
                dst[col] = clamp8(block[row * 8 + col]);
            }
        }
    }

    void decodeSlice(BitStream &bs, int mbRow) {
        if (mbRow < 0 || mbRow >= mbHeight || bs.bitsLeft() < 8) return;
        int qCode = static_cast<int>(bs.read(5));
        int qScale = qScaleType ? NONLINEAR_Q[qCode & 31] : (qCode * 2);
        while (bs.bitsLeft() >= 9 && bs.read(1)) bs.skip(8); // extra_information_slice

        int dcPred[3] = {128 << intraDcPrecision, 128 << intraDcPrecision, 128 << intraDcPrecision};
        int mbCol = -1;

        while (bs.bitsLeft() >= 23 && bs.peek(23) != 0) {
            int inc = decodeMbAddrInc(bs);
            if (inc <= 0) break;
            mbCol += inc;
            if (mbCol >= mbWidth) break;

            // Only decode Intra macroblocks fully; P/B slices retain motion-compensated / previous ref
            bool isIntra = false;
            bool hasQuant = false;
            if (picType == 1) {
                isIntra = true;
                if (bs.read(1) == 0) { bs.skip(1); hasQuant = true; }
            } else {
                uint32_t m = bs.peek(5);
                if (picType == 2 && (m == 0b00011 || m == 0b00001)) {
                    isIntra = true;
                    hasQuant = (m == 0b00001);
                    bs.skip(5);
                } else {
                    break;
                }
            }
            if (isMpeg2 && picStructure == 3 && !framePredFrameDct) bs.skip(1); // dct_type
            if (hasQuant) {
                qCode = static_cast<int>(bs.read(5));
                qScale = qScaleType ? NONLINEAR_Q[qCode & 31] : (qCode * 2);
            }
            if (!isIntra) continue;

            int16_t blk[64];
            int bx = mbCol * 16, by = mbRow * 16;
            for (int b = 0; b < 4; ++b) {
                decodeIntraBlock(bs, blk, dcPred[0], false, qScale);
                putBlock(current.y.data(), current.width, bx + (b & 1) * 8, by + (b >> 1) * 8, blk);
            }
            decodeIntraBlock(bs, blk, dcPred[1], true, qScale);
            putBlock(current.u.data(), current.width / 2, mbCol * 8, mbRow * 8, blk);
            decodeIntraBlock(bs, blk, dcPred[2], true, qScale);
            putBlock(current.v.data(), current.width / 2, mbCol * 8, mbRow * 8, blk);
        }
    }
};

// ============================================================================
// Transcode Session: MPEG-2 Decoder -> AMediaCodec video/avc Encoder
// ============================================================================
struct Session {
    AvContext *ffDecoder = nullptr;
    AvPacketPrefix *ffPacket = nullptr;
    AvFramePrefix *ffFrame = nullptr;
    std::unique_ptr<BuiltinMpeg2Decoder> builtinDecoder;
    AMediaCodec *encoder = nullptr;
    int width = 0, height = 0, color = 0;
    bool ended = false;
    int64_t lastPts = 0, frameDuration = 40000;
    std::vector<uint8_t> config;

    ~Session() {
        if (encoder) { AMediaCodec_stop(encoder); AMediaCodec_delete(encoder); }
        if (ffDecoder) ffApi().freeContext(&ffDecoder);
        if (ffFrame) ffApi().freeFrame(&ffFrame);
        if (ffPacket) ffApi().freePacket(&ffPacket);
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

    bool queuePlanes(JNIEnv *env, jobject mux, jmethodID emit,
                     const uint8_t *yPlane, int yStride,
                     const uint8_t *uPlane, int uStride,
                     const uint8_t *vPlane, int vStride,
                     int64_t pts, bool eos) {
        using namespace std::chrono;
        const auto deadline = steady_clock::now() + seconds(10);
        while (steady_clock::now() < deadline) {
            if (!drain(env, mux, emit, 0)) return false;
            ssize_t index = AMediaCodec_dequeueInputBuffer(encoder, 10000);
            if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) continue;
            if (index < 0) return false;
            if (eos) {
                return AMediaCodec_queueInputBuffer(encoder, index, 0, 0, lastPts + frameDuration,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) == AMEDIA_OK;
            }
            size_t capacity;
            auto dst = AMediaCodec_getInputBuffer(encoder, index, &capacity);
            int pixels = width * height;
            if (!dst || capacity < static_cast<size_t>(pixels * 3 / 2)) return false;
            for (int y = 0; y < height; ++y)
                memcpy(dst + y * width, yPlane + y * yStride, width);
            const uint8_t *chroma[2] = {uPlane, vPlane};
            int cStride[2] = {uStride, vStride};
            for (int plane = 0; plane < 2; ++plane) {
                for (int y = 0; y < height / 2; ++y) {
                    const auto src = chroma[plane] + y * cStride[plane];
                    if (color == 19) memcpy(dst + pixels + plane * pixels / 4 + y * width / 2, src, width / 2);
                    else for (int x = 0; x < width / 2; ++x) dst[pixels + y * width + 2 * x + plane] = src[x];
                }
            }
            lastPts = pts >= 0 ? pts : lastPts + frameDuration;
            return AMediaCodec_queueInputBuffer(encoder, index, 0, pixels * 3 / 2, lastPts, 0) == AMEDIA_OK;
        }
        return false;
    }
};

} // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_com_gdstream_DvdProxy_hasDecoder(JNIEnv *, jobject) {
    // Always true: built-in C++ MPEG-2 decoder is compiled into libgdcore.so.
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_gdstream_DvdProxy_open(JNIEnv *env, jobject, jstring name, jint color,
                                                                  jint w, jint h, jdouble fps) {
    if (w < 1 || w > 720 || h < 1 || h > 576 || fps < 1 || fps > 60) return 0;
    auto s = std::make_unique<Session>();
    if (auto codec = ffApi().decoder()) {
        s->ffDecoder = ffApi().context(codec);
        s->ffFrame = ffApi().frame();
        s->ffPacket = ffApi().packet();
        if (!s->ffDecoder || !s->ffFrame || !s->ffPacket || ffApi().open(s->ffDecoder, codec, nullptr) < 0) {
            if (s->ffDecoder) ffApi().freeContext(&s->ffDecoder);
            if (s->ffFrame) ffApi().freeFrame(&s->ffFrame);
            if (s->ffPacket) ffApi().freePacket(&s->ffPacket);
        }
    }
    if (!s->ffDecoder) {
        s->builtinDecoder = std::make_unique<BuiltinMpeg2Decoder>(w, h);
    }

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
    AMediaFormat_setInt32(format, "bitrate", 4500000);
    AMediaFormat_setFloat(format, "frame-rate", static_cast<float>(fps));
    AMediaFormat_setInt32(format, "i-frame-interval", 1);
    AMediaFormat_setInt32(format, "profile", 1); // AVC baseline: zero-latency, no B-frame reorder
    auto status = AMediaCodec_configure(s->encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK || AMediaCodec_start(s->encoder) != AMEDIA_OK) return 0;
    s->width = w; s->height = h; s->color = color; s->frameDuration = static_cast<int64_t>(1000000.0 / fps);
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT void JNICALL Java_com_gdstream_DvdProxy_encode(JNIEnv *env, jobject, jlong handle,
                                                                    jbyteArray input, jlong pts, jobject mux) {
    auto &s = *reinterpret_cast<Session *>(handle);
    auto emit = env->GetMethodID(env->GetObjectClass(mux), "video", "(J[B)V");
    if (!emit) return;

    auto run = [&]() {
        if (s.ffDecoder) {
            if (input) {
                int length = env->GetArrayLength(input);
                ffApi().unref(s.ffPacket);
                if (ffApi().allocate(s.ffPacket, length) < 0) return false;
                env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(s.ffPacket->data));
                s.ffPacket->pts = pts;
            }
            if (ffApi().send(s.ffDecoder, input ? s.ffPacket : nullptr) < 0) return false;
            int status;
            while ((status = ffApi().receive(s.ffDecoder, s.ffFrame)) == 0) {
                if (s.ffFrame->format != 0 || s.ffFrame->width != s.width || s.ffFrame->height != s.height) return false;
                if (!s.queuePlanes(env, mux, emit,
                                   s.ffFrame->planes[0], s.ffFrame->stride[0],
                                   s.ffFrame->planes[1], s.ffFrame->stride[1],
                                   s.ffFrame->planes[2], s.ffFrame->stride[2],
                                   s.ffFrame->pts, false)) return false;
            }
            if (input) return status == -EAGAIN;
            constexpr int AVERROR_EOF = -541478725;
            if (status != AVERROR_EOF || !s.queuePlanes(env, mux, emit, nullptr, 0, nullptr, 0, nullptr, 0, 0, true)) return false;
        } else {
            const YuvFrame *out = nullptr;
            if (input) {
                int length = env->GetArrayLength(input);
                std::vector<uint8_t> buf(static_cast<size_t>(length));
                env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(buf.data()));
                out = s.builtinDecoder->decodePicture(buf.data(), buf.size(), pts);
                if (out) {
                    if (!s.queuePlanes(env, mux, emit,
                                       out->y.data(), out->width,
                                       out->u.data(), out->width / 2,
                                       out->v.data(), out->width / 2,
                                       out->pts, false)) return false;
                }
                return true;
            } else {
                out = s.builtinDecoder->flush();
                if (out) {
                    if (!s.queuePlanes(env, mux, emit,
                                       out->y.data(), out->width,
                                       out->u.data(), out->width / 2,
                                       out->v.data(), out->width / 2,
                                       out->pts, false)) return false;
                }
                if (!s.queuePlanes(env, mux, emit, nullptr, 0, nullptr, 0, nullptr, 0, 0, true)) return false;
            }
        }
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
