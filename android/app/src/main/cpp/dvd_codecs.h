#pragma once
#include <cstdint>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/audio_fifo.h>
#include <libswresample/swresample.h>
}

namespace dvd {
// One instance per logical audio track, independent of the lifetime of the AVC encoder.
class Audio {
public:
    Audio() = default;
    Audio(const Audio &) = delete;
    ~Audio();
    void open(int substream);
    std::vector<uint8_t> convert(const uint8_t *data, int size, bool last);
private:
    AVCodecContext *decoder = nullptr, *encoder = nullptr;
    AVCodecParserContext *parser = nullptr;
    SwrContext *swr = nullptr;
    AVAudioFifo *fifo = nullptr;
    AVChannelLayout inputLayout{};
    int inputFormat = -1, inputRate = 0;
    int64_t samples = 0;

    void decode(const uint8_t *data, int size, std::vector<uint8_t> &out);
    void resample(AVFrame *frame);
    void encode(std::vector<uint8_t> &out, bool last);
};

struct Display {
    int64_t show = 0, stop = 0; // DCSQ offsets in 90 kHz ticks, zero stop means open-ended.
    std::vector<uint8_t> segments;
    int x = 0, y = 0, w = 0, h = 0;
    std::vector<int32_t> argb;
};
Display subpicture(const uint8_t *data, int size, const int32_t *clut, int width, int height, bool bitmap = false);
}
