cmake_minimum_required(VERSION 3.22.1)
project(sidekick LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_FLAGS_DEBUG "-g -O2" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-g -O2" CACHE STRING "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_CONFIG core.longpaths=true
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(whisper)
add_library(sidekick SHARED bridge.cpp)
target_link_libraries(sidekick PRIVATE whisper android log)
target_link_options(sidekick PRIVATE "-Wl,-z,max-page-size=16384")
