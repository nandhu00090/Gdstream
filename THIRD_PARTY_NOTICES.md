# Bundled audio decoder

GDStream includes `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`, matching
AndroidX Media3 1.8.0. Jellyfin publishes this artifact under GPL-3.0. Its license
and source notice are also packaged in the app's `assets/licenses/` directory.

- Publisher/source: https://github.com/jellyfin/jellyfin-androidx-media/tree/v1.8.0%2B1
- Pinned source commit: `84ef4b7201a9e1edfc699c31acbc505511a4af1f`
- Build instructions: `build.sh` in that tag; clone recursively to obtain its
  pinned FFmpeg and AndroidX Media submodules, then use the upstream Gradle build.
- Maven artifact and source JAR: https://repo.maven.apache.org/maven2/org/jellyfin/media3/media3-ffmpeg-decoder/1.8.0+1/
- AndroidX Media Java/JNI sources retain their Apache-2.0 notices; FFmpeg retains
  its component copyright and license notices in the pinned source submodule.

The AAR includes `libffmpegJNI.so` for arm64-v8a, armeabi-v7a, x86 and x86_64.
Its enabled `dca` decoder handles DTS core and DTS-HD, including MA residuals.
The app's HDMV LPCM packet reader converts big-endian samples, padding channels,
and Blu-ray channel order to raw PCM for Media3's audio sink. It does not send
HDMV LPCM packets to a nonexistent Android MediaCodec decoder.

## External HLS audio encoder

DVD audio and subpicture conversion and standalone BDAV DTS renditions use the FFmpegKit Android distribution
`com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1` for software decoding, resampling
and AC-3 encoding. The DVD bridge also uses its DVD subtitle decoder and DVB subtitle encoder.
Its CMake build uses the public headers from [FFmpeg 6.0](https://github.com/FFmpeg/FFmpeg/tree/n6.0).
The published native build enables GPL components and ships
the GPL-3.0 text and component notices in its Android resources.
[Publisher, sources and build instructions](https://github.com/moizhassankh/ffmpeg-kit-android-16KB)

## Dolby Vision RPU conversion and test vectors

`DolbyVisionRpu.kt` adapts the Profile 7/8 RPU syntax and mode 2 conversion
semantics from [quietvoid/dovi_tool](https://github.com/quietvoid/dovi_tool).
The MEL/FEL input and expected Profile 8.1 vectors under
`android/app/src/test/resources/dovi` are from the same project.

MIT License

Copyright (c) 2026 quietvoid

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## DVD MPEG-2 video decoder

GDStream builds the portable C decoder from libmpeg2 0.5.1 into libgdcore.
The sources in `android/app/src/main/cpp/libmpeg2` come from Debian
mpeg2dec 0.5.1-11 with an Android config.h. The original files retain their
copyright notices and GPL-2.0-or-later license. The license is packaged in
`assets/licenses/libmpeg2-GPL-2.0.txt`.

Source package: https://deb.debian.org/debian/pool/main/m/mpeg2dec/mpeg2dec_0.5.1-11.dsc
