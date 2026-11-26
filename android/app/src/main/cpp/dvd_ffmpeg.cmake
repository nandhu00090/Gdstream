# FFmpegKit 6.1.1 ships FFmpeg 6.0 shared objects in the APK without public headers or Prefab.
# Use the matching upstream headers and link against the same AAR that Gradle packages.
include(FetchContent)
FetchContent_Declare(dvd_ffmpeg_headers
    URL https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n6.0
    TLS_VERIFY ON
    URL_HASH SHA256=9a1fa94608300e835bf89bdfcd7d77deceba67bff3494f609816f84bd69a459e)
FetchContent_MakeAvailable(dvd_ffmpeg_headers)
set(include_dir "${CMAKE_CURRENT_BINARY_DIR}/dvd-include")
file(MAKE_DIRECTORY "${include_dir}/libavutil")
# Both supported Android ARM ABIs are little-endian and allow unaligned loads.
file(WRITE "${include_dir}/libavutil/avconfig.h" "#pragma once\n#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n")
target_include_directories(gdcore SYSTEM PRIVATE "${include_dir}" "${dvd_ffmpeg_headers_SOURCE_DIR}")

set(DVD_FFMPEG_AAR "" CACHE FILEPATH "FFmpegKit 16KB 6.1.1 AAR for standalone NDK builds")
if(NOT DVD_FFMPEG_AAR)
    set(gradle_cache "$ENV{GRADLE_USER_HOME}")
    if(NOT gradle_cache)
        set(gradle_cache "$ENV{HOME}/.gradle")
    endif()
    file(GLOB aars "${gradle_cache}/caches/modules-2/files-2.1/com.moizhassan.ffmpeg/ffmpeg-kit-16kb/6.1.1/*/*.aar")
    if(aars)
        list(GET aars 0 DVD_FFMPEG_AAR)
    else()
        set(DVD_FFMPEG_AAR "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg-kit-16kb-6.1.1.aar")
        file(DOWNLOAD "https://repo.maven.apache.org/maven2/com/moizhassan/ffmpeg/ffmpeg-kit-16kb/6.1.1/ffmpeg-kit-16kb-6.1.1.aar"
             "${DVD_FFMPEG_AAR}" EXPECTED_HASH SHA256=5b8da42b87d2e63292112f81c2611d4c208bade75c591426cae0d4c5c959d522 TLS_VERIFY ON)
    endif()
endif()
set(native_dir "${CMAKE_CURRENT_BINARY_DIR}/dvd-libs")
foreach(lib avcodec swresample avutil)
    set(entry "jni/${ANDROID_ABI}/lib${lib}.so")
    if(NOT EXISTS "${native_dir}/${entry}")
        file(ARCHIVE_EXTRACT INPUT "${DVD_FFMPEG_AAR}" DESTINATION "${native_dir}" PATTERNS "${entry}")
    endif()
    target_link_libraries(gdcore PRIVATE "${native_dir}/${entry}")
endforeach()
