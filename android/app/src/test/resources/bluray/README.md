`hdmv.ts.bin` is a synthetic 120 ms black video / silent 5.1 LPCM + DTS fixture.
No movie or other third-party media is included. Generated with FFmpeg 7.1.5:

```
ffmpeg -f lavfi -i color=c=black:s=64x64:r=25 \
  -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
  -t 0.12 -map 0:v -map 1:a -map 1:a -c:v libx264 -preset ultrafast \
  -c:a:0 pcm_bluray -c:a:1 dca -strict -2 -mpegts_m2ts_mode 1 -f mpegts generated.m2ts
```

Remove the first 4 bytes of each 192-byte M2TS packet, exactly as the ISO proxy
serves it. Keep the original HDMV PMT (LPCM 0x80 and DTS 0x82). Tests also build
synthetic DTS-HD EXTSS headers to exercise core+extension sample assembly across
PES boundaries; those headers are structural fixtures, not decodable MA audio.

`dual-dv.ts.bin` contains six synthetic 64x64 black HEVC frames on PID 0x1011,
plus metadata-only MEL RPUs (see ../dovi/README.md) on PID 0x1015. Its synthetic
PMT declares both as HEVC; tests supply UHD CLPI descriptors. The EL PES contains
discarded VCL/type-63 filler to verify filtering, with alternating PID arrival
order and reordered PTS (0,3,2,1,5,4 at 24 fps). TS packetization fragments RPUs.
The base was generated with:

    ffmpeg -f lavfi -i color=c=black:s=64x64:r=24 -frames:v 6 -c:v libx265 \
      -preset ultrafast -x265-params 'pools=1:frame-threads=1:aud=1:repeat-headers=1:bframes=2' -f hevc base.hevc

Then run `python3 scripts/generate-dv-fixture.py base.hevc` from the repository root.
This tests demuxing and metadata injection, not UHD hardware decoding.

`silent.ac3.bin` and `silent.eac3.bin` are one synthetic stereo silence frame each
(48 kHz, 192 kbps), generated with FFmpeg using `anullsrc`, `-frames:a 1`, and
`-c:a ac3 -f ac3` / `-c:a eac3 -f eac3`. The extractor regression packetizes these
alongside LPCM/DTS into all 32 Blu-ray audio PIDs, including Tamil PID 0x1106.
