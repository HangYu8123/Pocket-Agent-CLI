cmake_minimum_required(VERSION 3.22)
project(pocketwhisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# The app is distributed as a debug build; make sure the inference code is still optimised.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(pocketwhisper SHARED whisper_jni.cpp)
target_link_libraries(pocketwhisper PRIVATE whisper log)
target_compile_options(pocketwhisper PRIVATE -O2 -fvisibility=hidden)
# 16 KB page-size compatibility for newer Android devices.
target_link_options(pocketwhisper PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
