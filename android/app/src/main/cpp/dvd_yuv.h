#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace dvd {
constexpr int I420 = 19;
constexpr int NV12 = 21;

// All strides are in bytes. libmpeg2 returns planar 4:2:0 in macroblock-padded planes.
inline size_t pack420(uint8_t *dst, size_t capacity, int color, int stride, int sliceHeight,
                      int width, int height, const uint8_t *y, int yStride,
                      const uint8_t *u, const uint8_t *v, int chromaStride, bool blend = false) {
    if (!dst || stride < width || sliceHeight < height || ((stride | sliceHeight) & 1) ||
        (color != I420 && color != NV12)) return 0;
    const size_t ySize = static_cast<size_t>(stride) * sliceHeight;
    const size_t size = ySize + ySize / 2;
    if (capacity < size) return 0;
    if (stride != width || sliceHeight != height) {
        memset(dst, 16, ySize);
        memset(dst + ySize, 128, ySize / 2);
    }
    auto plane = [blend](uint8_t *out, int outStride, int step, const uint8_t *src,
                         int srcStride, int columns, int rows) {
        for (int row = 0; row < rows; ++row, src += srcStride, out += outStride) {
            if (blend) {
                const auto *above = row > 0 ? src - srcStride : src;
                const auto *below = row + 1 < rows ? src + srcStride : src;
                for (int x = 0; x < columns; ++x)
                    out[x * step] = (above[x] + 2 * src[x] + below[x]) >> 2;
            } else if (step == 1) {
                memcpy(out, src, columns);
            } else {
                for (int x = 0; x < columns; ++x) out[x * step] = src[x];
            }
        }
    };
    plane(dst, stride, 1, y, yStride, width, height);
    const int step = color == I420 ? 1 : 2;
    const int uvStride = stride / 2 * step;
    plane(dst + ySize, uvStride, step, u, chromaStride, width / 2, height / 2);
    plane(dst + ySize + (color == I420 ? ySize / 4 : 1), uvStride, step,
          v, chromaStride, width / 2, height / 2);
    return size;
}
}
