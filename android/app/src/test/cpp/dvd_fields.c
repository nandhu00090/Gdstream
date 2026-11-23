#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "mpeg2.h"

/* A 16x32 sequence at 25 fps. Each intra field contains one constant macroblock.
 * Alternate the first field's parity and end the segment with an unpaired field. */
int main(void) {
    const uint8_t sequence[] = {0,0,1,0xb3,1,0,0x20,0x23,0,2,0x20,0x80,0,0,1,0xb5,0x14,0x82,0,1,0,0};
    const uint8_t field[] = {0,0,1,0,0,0x0f,0xff,0xf8,0,0,1,0xb5,0x8f,0xff,0xf1,0,0,0,0,1,1,0x13,0x94,0xa5,0x22,0x20};
    const uint8_t end[] = {0,0,1,0xb7};
    uint8_t stream[sizeof(sequence) + 241 * sizeof(field) + sizeof(end)];
    uint8_t *pos = stream;
    memcpy(pos, sequence, sizeof(sequence));
    pos += sizeof(sequence);
    for (int i = 0; i < 241; ++i) {
        unsigned frame = i / 2;
        memcpy(pos, field, sizeof(field));
        pos[4] = frame >> 2;
        pos[5] |= (frame & 3) << 6;
        pos[14] = ((frame ^ i) & 1) ? 0xf2 : 0xf1;
        pos += sizeof(field);
    }
    memcpy(pos, end, sizeof(end));

    mpeg2dec_t *decoder = mpeg2_init();
    assert(decoder);
    mpeg2_buffer(decoder, stream, stream + sizeof(stream));
    unsigned frames = 0;
    mpeg2_state_t state;
    while ((state = mpeg2_parse(decoder)) != STATE_BUFFER) {
        if (state != STATE_SLICE_1ST && state != STATE_SLICE && state != STATE_END && state != STATE_INVALID_END) continue;
        unsigned fields = mpeg2_take_display_fields(decoder);
        if (fields == 0) continue;
        const mpeg2_picture_t *picture = mpeg2_info(decoder)->display_picture;
        assert(fields == 2 && picture->temporal_reference == frames);
        assert((uint64_t)picture->frame_period * fields / 600 == 3600);
        assert(mpeg2_take_display_fields(decoder) == 0);
        ++frames;
    }
    assert(frames == 120);
    mpeg2_close(decoder);
    return 0;
}
