cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp vendored sources (github.com/ggml-org/whisper.cpp, MIT)
set(WHISER_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Runtime CPU-feature dispatch (Tensor G5 pitfall): ggml builds
# android_armv8.0/8.2/8.6/9.x backend variants as separate .so files and
# picks the best supported at runtime via ggml_backend_score — a variant
# with unsupported dotprod/i8mm instructions is never dlopen'ed.
add_subdirectory(${WHISER_SRC_DIR} whisper-build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED jni_bridge.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISER_SRC_DIR}/include
    ${WHISER_SRC_DIR}/ggml/include)

target_link_libraries(whisper_jni PRIVATE
    whisper
    ggml
    android
    log)

target_compile_definitions(whisper_jni PRIVATE
    GGML_BACKEND_DL)

# ---------------------------------------------------------------------------
# llama.cpp (локальная суммаризация): отдельная сборка через ExternalProject,
# чтобы её ggml не конфликтовал с ggml от whisper (одноимённые цели).
# Статический CPU-бэкенд, без DL-вариантов; символы прячутся (--exclude-libs).
# ---------------------------------------------------------------------------
include(ExternalProject)

set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
set(LLAMA_BLD ${CMAKE_CURRENT_BINARY_DIR}/llama-build)

ExternalProject_Add(llama_ext
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    PATCH_COMMAND ""
    SOURCE_DIR ${LLAMA_SRC}
    BINARY_DIR ${LLAMA_BLD}
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -S ${LLAMA_SRC} -B ${LLAMA_BLD}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=${ANDROID_STL}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DBUILD_SHARED_LIBS=OFF
        -DGGML_NATIVE=OFF
        -DGGML_OPENMP=OFF
        -DGGML_BACKEND_DL=OFF
        -DGGML_CCACHE=OFF
        -DLLAMA_CURL=OFF
        -DLLAMA_BUILD_TESTS=OFF
        -DLLAMA_BUILD_EXAMPLES=OFF
        -DLLAMA_BUILD_TOOLS=OFF
        -DLLAMA_BUILD_APP=OFF
        -DLLAMA_BUILD_UI=OFF
        -DLLAMA_BUILD_COMMON=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build ${LLAMA_BLD} -j 4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS
        ${LLAMA_BLD}/src/libllama.a
        ${LLAMA_BLD}/ggml/src/libggml.a
        ${LLAMA_BLD}/ggml/src/libggml-base.a
        ${LLAMA_BLD}/ggml/src/libggml-cpu.a
)

add_library(llama_static STATIC IMPORTED)
set_target_properties(llama_static PROPERTIES IMPORTED_LOCATION ${LLAMA_BLD}/src/libllama.a)
add_library(ggml_static STATIC IMPORTED)
set_target_properties(ggml_static PROPERTIES IMPORTED_LOCATION ${LLAMA_BLD}/ggml/src/libggml.a)
add_library(ggml_base_static STATIC IMPORTED)
set_target_properties(ggml_base_static PROPERTIES IMPORTED_LOCATION ${LLAMA_BLD}/ggml/src/libggml-base.a)
add_library(ggml_cpu_static STATIC IMPORTED)
set_target_properties(ggml_cpu_static PROPERTIES IMPORTED_LOCATION ${LLAMA_BLD}/ggml/src/libggml-cpu.a)

add_library(llama_jni SHARED jni_llama.cpp)
add_dependencies(llama_jni llama_ext)
target_include_directories(llama_jni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include)
target_link_libraries(llama_jni PRIVATE
    llama_static ggml_static ggml_cpu_static ggml_base_static
    android log)
# внутренности ggml/llama не должны интерпировать символы whisper-ggml
target_link_options(llama_jni PRIVATE
    "-Wl,--exclude-libs,ALL"
    "-Wl,--start-group" $<TARGET_FILE:llama_static> $<TARGET_FILE:ggml_static>
    $<TARGET_FILE:ggml_cpu_static> $<TARGET_FILE:ggml_base_static> "-Wl,--end-group")
