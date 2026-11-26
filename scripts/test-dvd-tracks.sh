#!/usr/bin/env bash
# Run the DVD TS / bitmap integration test using the production JNI entry points on a Linux JVM.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
src=${DVD_FFMPEG_SOURCE:-}
if [[ -z "$src" ]]; then
    curl -fsSL --retry 2 https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n6.0 -o "$scratch/ffmpeg.tar.gz"
    echo "9a1fa94608300e835bf89bdfcd7d77deceba67bff3494f609816f84bd69a459e  $scratch/ffmpeg.tar.gz" | sha256sum -c -
    tar xzf "$scratch/ffmpeg.tar.gz" -C "$scratch"
    src="$scratch/FFmpeg-n6.0"
fi
build=${DVD_FFMPEG_BUILD:-"$scratch/build"}
if [[ ! -f "$build/libavcodec/libavcodec.a" ]]; then
    mkdir -p "$build"
    (cd "$build" && "$src/configure" --disable-everything --disable-asm --enable-pic --disable-debug \
        --disable-autodetect --disable-doc --disable-programs --disable-network --disable-avdevice \
        --disable-avformat --disable-avfilter --disable-swscale --enable-parser=dca,mpegaudio \
        --enable-decoder=dca,pcm_dvd,mp2,dvdsub --enable-encoder=ac3,dvbsub && make -j4)
fi
native="$repo/android/app/src/main/cpp"
"${CXX:-c++}" -std=c++17 -O2 -shared -fPIC -I"${JAVA_HOME:?JDK required}/include" -I"$JAVA_HOME/include/linux" \
    -I"$build" -I"$src" "$native/dvd_codecs.cpp" "$native/dvd_transcode.cpp" \
    "$build/libavcodec/libavcodec.a" "$build/libswresample/libswresample.a" "$build/libavutil/libavutil.a" \
    -lm -pthread -o "$scratch/libdvd-tracks.so"
cd "$repo/android"
GDSTREAM_TEST_DVD_JNI="$scratch/libdvd-tracks.so" bash gradlew :app:testDebugUnitTest --rerun \
    --tests com.gdstream.DvdTracksTest "$@"
