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
