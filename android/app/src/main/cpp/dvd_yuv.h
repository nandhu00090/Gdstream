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
                      const uint8_t *u, const uint8_t *v, int chromaStride) {
    if (!dst || stride < width || sliceHeight < height || ((stride | sliceHeight) & 1) ||
        (color != I420 && color != NV12)) return 0;
    const size_t ySize = static_cast<size_t>(stride) * sliceHeight;
    const size_t size = ySize + ySize / 2;
    if (capacity < size) return 0;
    if (stride != width || sliceHeight != height) {
        memset(dst, 16, ySize);
        memset(dst + ySize, 128, ySize / 2);
    }
    for (int row = 0; row < height; ++row)
        memcpy(dst + row * stride, y + row * yStride, width);
    uint8_t *chroma = dst + ySize;
    for (int row = 0; row < height / 2; ++row) {
        if (color == I420) {
            memcpy(chroma + row * (stride / 2), u, width / 2);
            memcpy(chroma + ySize / 4 + row * (stride / 2), v, width / 2);
        } else {
            for (int x = 0; x < width / 2; ++x) {
                chroma[row * stride + 2 * x] = u[x];
                chroma[row * stride + 2 * x + 1] = v[x];
            }
        }
        u += chromaStride;
        v += chromaStride;
    }
    return size;
}
}
