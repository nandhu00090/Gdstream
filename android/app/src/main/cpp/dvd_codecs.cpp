#include "dvd_codecs.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>

extern "C" {
#include <libavutil/opt.h>
}

namespace dvd {
namespace {
void check(int result, const char *operation) {
    if (result >= 0) return;
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(result, message, sizeof(message));
    throw std::runtime_error(std::string(operation) + ": " + message);
}
AVCodecContext *context(const AVCodec *codec) {
    if (!codec) throw std::runtime_error("DVD codec unavailable");
    auto ctx = avcodec_alloc_context3(codec);
    if (!ctx) throw std::bad_alloc();
    return ctx;
}
struct Free {
    void operator()(AVFrame *p) const { av_frame_free(&p); }
    void operator()(AVPacket *p) const { av_packet_free(&p); }
    void operator()(AVCodecContext *p) const { avcodec_free_context(&p); }
};
auto frame() {
    std::unique_ptr<AVFrame, Free> f(av_frame_alloc());
    if (!f) throw std::bad_alloc();
    return f;
}
auto packet() {
    std::unique_ptr<AVPacket, Free> p(av_packet_alloc());
    if (!p) throw std::bad_alloc();
    return p;
}
auto pcmFrame(AVCodecContext *enc, int count) {
    auto f = frame();
    f->format = enc->sample_fmt;
    f->sample_rate = enc->sample_rate;
    check(av_channel_layout_copy(&f->ch_layout, &enc->ch_layout), "AC-3 layout");
    f->nb_samples = count;
    check(av_frame_get_buffer(f.get(), 0), "AC-3 samples");
    return f;
}
}

Audio::~Audio() {
    avcodec_free_context(&decoder);
    avcodec_free_context(&encoder);
    if (parser) av_parser_close(parser);
    swr_free(&swr);
    if (fifo) av_audio_fifo_free(fifo);
    av_channel_layout_uninit(&inputLayout);
}

void Audio::open(int id) {
    AVCodecID codec = id >= 0xc0 ? AV_CODEC_ID_MP2 : id >= 0xa0 ? AV_CODEC_ID_PCM_DVD : AV_CODEC_ID_DTS;
    decoder = context(avcodec_find_decoder(codec));
    check(avcodec_open2(decoder, decoder->codec, nullptr), "DVD audio decoder");
    if (codec != AV_CODEC_ID_PCM_DVD) {
        parser = av_parser_init(codec);
        if (!parser) throw std::runtime_error("Missing DVD audio frame parser");
    }
}

void Audio::resample(AVFrame *input) {
    if (!encoder) {
        encoder = context(avcodec_find_encoder(AV_CODEC_ID_AC3));
        encoder->sample_rate = 48000;
        encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
        encoder->time_base = {1, 48000};
        av_channel_layout_default(&encoder->ch_layout, std::clamp(input->ch_layout.nb_channels, 1, 6));
        for (auto layout = encoder->codec->ch_layouts; layout && layout->nb_channels; ++layout) {
            if (!av_channel_layout_compare(layout, &input->ch_layout)) {
                check(av_channel_layout_copy(&encoder->ch_layout, layout), "DVD audio layout");
                break;
            }
        }
        encoder->bit_rate = encoder->ch_layout.nb_channels <= 2 ? 192000 : 448000;
        check(avcodec_open2(encoder, encoder->codec, nullptr), "DVD AC-3 encoder");
        fifo = av_audio_fifo_alloc(encoder->sample_fmt, encoder->ch_layout.nb_channels, encoder->frame_size);
        if (!fifo) throw std::bad_alloc();
    }
    if (input && (inputRate != input->sample_rate || inputFormat != input->format ||
                  av_channel_layout_compare(&inputLayout, &input->ch_layout))) {
        if (swr) resample(nullptr);
        swr_free(&swr);
        inputRate = input->sample_rate;
        inputFormat = input->format;
        check(av_channel_layout_copy(&inputLayout, &input->ch_layout), "DVD input layout");
        check(swr_alloc_set_opts2(&swr, &encoder->ch_layout, encoder->sample_fmt, 48000,
            &inputLayout, static_cast<AVSampleFormat>(inputFormat), inputRate, 0, nullptr), "DVD resampler");
        check(swr_init(swr), "DVD resampler initialization");
    }
    const int count = input ? input->nb_samples : 0;
    const int capacity = swr_get_out_samples(swr, count);
    check(capacity, "DVD resampler capacity");
    if (!capacity) return;
    auto converted = pcmFrame(encoder, capacity);
    int n = swr_convert(swr, converted->extended_data, capacity,
                        input ? const_cast<const uint8_t **>(input->extended_data) : nullptr, count);
    check(n, "DVD PCM conversion");
    if (n && av_audio_fifo_write(fifo, reinterpret_cast<void **>(converted->extended_data), n) != n)
        throw std::runtime_error("DVD PCM FIFO write failed");
}

void Audio::encode(std::vector<uint8_t> &out, bool last) {
    if (!encoder) return;
    auto pkt = packet();
    auto drain = [&]() {
        int rc;
        while ((rc = avcodec_receive_packet(encoder, pkt.get())) >= 0) {
            out.insert(out.end(), pkt->data, pkt->data + pkt->size);
            av_packet_unref(pkt.get());
        }
        if (rc != AVERROR(EAGAIN) && rc != AVERROR_EOF) check(rc, "DVD AC-3 output");
    };
    const int size = encoder->frame_size;
    while (av_audio_fifo_size(fifo) >= size || (last && av_audio_fifo_size(fifo))) {
        auto f = pcmFrame(encoder, size);
        int n = av_audio_fifo_read(fifo, reinterpret_cast<void **>(f->extended_data), size);
        check(n, "DVD PCM FIFO read");
        if (n < size) check(av_samples_set_silence(f->extended_data, n, size - n, encoder->ch_layout.nb_channels,
                                                   encoder->sample_fmt), "DVD final AC-3 padding");
        f->pts = samples;
        samples += size;
        check(avcodec_send_frame(encoder, f.get()), "DVD AC-3 encode");
        drain();
    }
    if (last) {
        check(avcodec_send_frame(encoder, nullptr), "DVD AC-3 flush");
        drain();
    }
}

void Audio::decode(const uint8_t *data, int size, std::vector<uint8_t> &out) {
    auto pkt = packet();
    pkt->data = const_cast<uint8_t *>(data);
    pkt->size = size;
    check(avcodec_send_packet(decoder, data ? pkt.get() : nullptr), "DVD audio decode");
    auto f = frame();
    int rc;
    while ((rc = avcodec_receive_frame(decoder, f.get())) >= 0) {
        resample(f.get());
        encode(out, false);
        av_frame_unref(f.get());
    }
    if (rc != AVERROR(EAGAIN) && rc != AVERROR_EOF) check(rc, "DVD audio frame");
}

std::vector<uint8_t> Audio::convert(const uint8_t *data, int size, bool last) {
    std::vector<uint8_t> out, padded(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (size) std::memcpy(padded.data(), data, size);
    if (parser) {
        int pos = 0;
        do {
            uint8_t *unit = nullptr;
            int length = 0;
            int used = av_parser_parse2(parser, decoder, &unit, &length, padded.data() + pos, size - pos,
                                        AV_NOPTS_VALUE, AV_NOPTS_VALUE, -1);
            check(used, "DVD audio framing");
            if (length) decode(unit, length, out);
            if (!length && !used && pos < size) throw std::runtime_error("Incomplete DVD audio packet");
            pos += used;
        } while (pos < size);
    } else if (size) {
        decode(padded.data(), size, out);
    }
    if (last) {
        decode(nullptr, 0, out);
        if (swr) resample(nullptr);
        encode(out, true);
    }
    return out;
}

Display subpicture(const uint8_t *data, int size, const int32_t *clut, int width, int height) {
    std::unique_ptr<AVCodecContext, Free> dec(context(avcodec_find_decoder(AV_CODEC_ID_DVD_SUBTITLE)));
    std::string palette;
    for (int i = 0; i < 16; ++i) {
        int y = 298 * (((clut[i] >> 16) & 255) - 16);
        int cr = ((clut[i] >> 8) & 255) - 128, cb = (clut[i] & 255) - 128;
        auto rgb = [y](int chroma) { return std::clamp((y + chroma + 128) >> 8, 0, 255); };
        char color[8];
        std::snprintf(color, sizeof(color), "%02x%02x%02x", rgb(409 * cr), rgb(-100 * cb - 208 * cr), rgb(516 * cb));
        if (i) palette += ',';
        palette += color;
    }
    check(av_opt_set(dec->priv_data, "palette", palette.c_str(), 0), "DVD subpicture palette");
    check(avcodec_open2(dec.get(), dec->codec, nullptr), "DVD subpicture decoder");
    auto pkt = packet();
    check(av_new_packet(pkt.get(), size), "DVD subpicture packet");
    std::memcpy(pkt->data, data, size);
    struct Decoded : AVSubtitle {
        Decoded() : AVSubtitle{} {}
        ~Decoded() { avsubtitle_free(this); }
    } sub;
    int got = 0;
    check(avcodec_decode_subtitle2(dec.get(), &sub, &got, pkt.get()), "DVD subpicture decode");
    Display display;
    if (!got || !sub.num_rects) return display;
    // libavcodec reports truncated milliseconds. Recover the original 1024-tick DCSQ units.
    auto ticks = [](uint32_t ms) { return ((ms * 90LL + 512) / 1024) * 1024; };
    display.show = ticks(sub.start_display_time);
    display.stop = ticks(sub.end_display_time);

    std::unique_ptr<AVCodecContext, Free> enc(context(avcodec_find_encoder(AV_CODEC_ID_DVB_SUBTITLE)));
    enc->width = width;
    enc->height = height;
    enc->time_base = {1, 90000};
    check(avcodec_open2(enc.get(), enc->codec, nullptr), "DVB subtitle encoder");

    // Bound each DVB object below the 16-bit segment/PES length. The FFmpeg DVB encoder
    // expects tightly packed, even-height fields; the DVD decoder can crop transparent edges.
    std::vector<AVSubtitleRect> bands;
    std::vector<std::vector<uint8_t>> pixels;
    for (unsigned i = 0; i < sub.num_rects; ++i) {
        const auto &r = *sub.rects[i];
        if (r.w <= 0 || r.h <= 0 || r.w > 720 || r.h > 576 || r.linesize[0] < r.w)
            throw std::runtime_error("Invalid DVD subpicture dimensions");
        for (int top = 0; top < r.h; top += 64) {
            int rows = std::min(64, r.h - top);
            bands.push_back(r);
            auto &band = bands.back();
            band.y += top;
            band.h = (rows + 1) & ~1;
            auto &buffer = pixels.emplace_back(r.w * band.h);
            for (int y = 0; y < rows; ++y)
                std::memcpy(buffer.data() + y * r.w, r.data[0] + (top + y) * r.linesize[0], r.w);
            if (rows & 1) {
                // Pad the odd field with a transparent palette entry.
                auto colors = reinterpret_cast<const uint32_t *>(r.data[1]);
                int transparent = 0;
                while (transparent < r.nb_colors && colors[transparent] >> 24) ++transparent;
                if (transparent == r.nb_colors && transparent < 256) ++band.nb_colors;
                std::memset(buffer.data() + rows * r.w, transparent, r.w);
            }
            band.data[0] = buffer.data();
            band.linesize[0] = r.w;
        }
    }
    std::vector<AVSubtitleRect *> rects;
    for (auto &band : bands) rects.push_back(&band);
    AVSubtitle view = sub;
    view.start_display_time = 0;
    view.rects = rects.data();
    view.num_rects = rects.size();
    size_t capacity = 1024;
    for (const auto &band : bands) capacity += 2 * band.w * band.h + 2048;
    display.segments.resize(capacity);
    int n = avcodec_encode_subtitle(enc.get(), display.segments.data(), display.segments.size(), &view);
    check(n, "DVB subtitle encoding");
    display.segments.resize(n);
    // Put region compositions before their CLUTs in the display set. FFmpeg 6
    // writes these two contiguous groups in the opposite order.
    int cluts = -1, regions = -1;
    for (int pos = 0; pos + 6 <= n; ) {
        int type = display.segments[pos + 1];
        if (type == 0x12 && cluts < 0) cluts = pos;
        if (type == 0x11 && regions < 0) regions = pos;
        if (type == 0x13 && cluts >= 0 && regions > cluts) {
            auto first = display.segments.begin();
            std::rotate(first + cluts, first + regions, first + pos);
            break;
        }
        pos += 6 + (display.segments[pos + 4] << 8) + display.segments[pos + 5];
    }
    return display;
}
}
