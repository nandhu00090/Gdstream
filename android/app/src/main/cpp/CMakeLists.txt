cmake_minimum_required(VERSION 3.18)
project(gdcore LANGUAGES C CXX)
add_library(gdcore SHARED gdcore.cpp dvd_transcode.cpp dvd_codecs.cpp
    libmpeg2/alloc.c libmpeg2/header.c libmpeg2/decode.c libmpeg2/slice.c
    libmpeg2/motion_comp.c libmpeg2/idct.c libmpeg2/cpu_accel.c libmpeg2/cpu_state.c)
target_compile_features(gdcore PRIVATE cxx_std_17)
target_compile_options(gdcore PRIVATE -fvisibility=hidden -O2)
target_link_libraries(gdcore PRIVATE log mediandk dl)
include(dvd_ffmpeg.cmake)
