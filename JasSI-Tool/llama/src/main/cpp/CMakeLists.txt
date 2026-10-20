cmake_minimum_required(VERSION 3.22.1)
project(jassi_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# dotprod = szybsze inferencje na telefonach z ~2018+. Jeśli stary telefon crashuje -> usuń tę linię.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

add_subdirectory(llama.cpp)

add_library(jassi_llama SHARED llama_jni.cpp)
target_include_directories(jassi_llama PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(jassi_llama PRIVATE llama android log)
# ggml jest linkowany statycznie i ukryty -> brak konfliktu symboli z whisper.cpp
target_link_options(jassi_llama PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
