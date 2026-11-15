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
