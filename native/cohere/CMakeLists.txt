cmake_minimum_required(VERSION 3.16)
project(voicenotes_cohere CXX)

# CrispASR must be configured as a top-level project (it uses CMAKE_SOURCE_DIR), so build.sh
# builds its `cohere` target first; this project links the resulting static archives.
set(CRISPASR_DIR "" CACHE PATH "CrispASR source tree")
set(CRISPASR_BUILD "" CACHE PATH "CrispASR build tree with the cohere target built")
if(NOT EXISTS "${CRISPASR_DIR}/src/cohere.h")
    message(FATAL_ERROR "CRISPASR_DIR does not point to a CrispASR checkout")
endif()

set(_archives
    "${CRISPASR_BUILD}/src/libcohere.a"
    "${CRISPASR_BUILD}/src/libcrispasr-core.a"
    "${CRISPASR_BUILD}/ggml/src/libggml.a"
    "${CRISPASR_BUILD}/ggml/src/libggml-cpu.a"
    "${CRISPASR_BUILD}/ggml/src/libggml-base.a")
foreach(_archive IN LISTS _archives)
    if(NOT EXISTS "${_archive}")
        message(FATAL_ERROR "Missing ${_archive}; build the CrispASR cohere target first")
    endif()
endforeach()

add_library(voicenotes_cohere SHARED cohere_jni.cpp)
set_target_properties(voicenotes_cohere PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_include_directories(voicenotes_cohere PRIVATE "${CRISPASR_DIR}/src" "${CRISPASR_DIR}/include")
target_compile_options(voicenotes_cohere PRIVATE -O2 -Wall -Wextra -Werror -ffunction-sections -fdata-sections)
target_link_libraries(voicenotes_cohere PRIVATE -Wl,--start-group ${_archives} -Wl,--end-group log dl m)
target_link_options(voicenotes_cohere PRIVATE
    -fopenmp -static-openmp
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
