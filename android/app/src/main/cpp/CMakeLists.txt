cmake_minimum_required(VERSION 3.22.1)
project(onda_rnnoise C)

add_library(onda_rnnoise SHARED
    rnnoise_jni.c
    rnnoise/src/denoise.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/pitch.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
)

target_include_directories(onda_rnnoise PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src
)

target_compile_options(onda_rnnoise PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_libraries(onda_rnnoise PRIVATE m)
target_link_options(onda_rnnoise PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
