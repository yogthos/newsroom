#!/bin/sh
# Build the speech engine, native/newsroom_tts.cpp and native/s3gen.cpp, against
# llama.cpp (native/llama.cpp, fetched at the pinned commit by the `jolt tts`
# task) and LAME (native/lame, built here from its release tarball). Run from
# the project root.
#
#   libnewsroom_tts.{dylib,so}   for jolt serve and jolt test
#   libnewsroom_tts.a            the same, every member inside, for jolt build
#   libnewsroom_tts_gpu.so       a GPU plugin, when NEWSROOM_TTS_GPU asks for one
#
# The engine linked into newsroom runs on the CPU, and on macOS on Metal too,
# which needs only the system's frameworks. Any other GPU backend needs a
# library a machine may not have (the NVIDIA driver, ROCm, the Vulkan loader),
# and a binary linked against one won't start without it, so CUDA, HIP and
# Vulkan go in the plugin: the whole engine again with that backend, its entry
# points renamed nrttsg_ (plugin_names.h) and everything else kept to itself,
# which newsroom.tts loads when it can and passes over when it can't.
#
#   NEWSROOM_TTS_GPU=metal|cuda|hip|vulkan|none
#
# metal by default on macOS, none elsewhere. llama.cpp is built for the kind
# of CPU, not the one machine: AVX2 and FMA on x86, NEON on ARM. Also builds
# native/build/test-s3gen and test-tts, the parity tests.
set -e
L=native/llama.cpp
B=native/build
mkdir -p "$B"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
OS=$(uname -s)
ARCH=$(uname -m)

GPU=${NEWSROOM_TTS_GPU:-}
if [ -z "$GPU" ]; then
  if [ "$OS" = Darwin ]; then GPU=metal; else GPU=none; fi
fi
case "$GPU" in
  metal) [ "$OS" = Darwin ] || { echo "Metal is macOS only" >&2; exit 1; } ;;
  cuda|hip|vulkan|none) ;;
  *) echo "unknown NEWSROOM_TTS_GPU: $GPU (metal, cuda, hip, vulkan or none)" >&2; exit 1 ;;
esac

# --- LAME, static and position independent -------------------------------------
LAME_VER=3.100
LAME_SHA=ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
LAME_DIR=native/lame/lame-$LAME_VER
if [ ! -f native/lame/install/lib/libmp3lame.a ]; then
  mkdir -p native/lame
  tarball=native/lame/lame-$LAME_VER.tar.gz
  if [ ! -f "$tarball" ]; then
    curl -sSfL -o "$tarball" "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz"
  fi
  sum=$( (shasum -a 256 "$tarball" 2>/dev/null || sha256sum "$tarball") | cut -d' ' -f1)
  if [ "$sum" != "$LAME_SHA" ]; then echo "lame tarball checksum mismatch: $sum" >&2; exit 1; fi
  rm -rf "$LAME_DIR"
  tar -xzf "$tarball" -C native/lame
  # 3.100's export list names a symbol it no longer has; only the shared
  # library reads it, but configure checks it either way
  sed -i.bak '/lame_init_old/d' "$LAME_DIR/include/libmp3lame.sym"
  prefix=$(cd native/lame && pwd)/install
  (cd "$LAME_DIR" && CFLAGS="-O2 -fPIC" ./configure --quiet --prefix="$prefix" \
     --disable-shared --enable-static --disable-frontend --disable-decoder --disable-gtktest \
   && make -s -j"$JOBS" && make -s install)
fi
LAME=native/lame/install/lib/libmp3lame.a

# --- llama.cpp, one build a backend ----------------------------------------------
# build_llama VARIANT: native/llama.cpp/build-VARIANT, with VARIANT's backend
build_llama() {
  v=$1
  dir=$L/build-$v
  if [ -f "$dir/src/libllama.a" ]; then return; fi
  set -- -DGGML_METAL=OFF
  case "$v" in
    metal)  set -- -DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON ;;
    vulkan) set -- "$@" -DGGML_VULKAN=ON ;;
    cuda)   set -- "$@" -DGGML_CUDA=ON ;;
    hip)    set -- "$@" -DGGML_HIP=ON ;;
  esac
  case "$ARCH" in
    x86_64|amd64) [ "$OS" = Darwin ] || set -- "$@" -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON ;;
  esac
  cmake -S "$L" -B "$dir" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ALL_VARIANTS=OFF \
    -DGGML_BLAS=OFF -DGGML_ACCELERATE=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_COMMON=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF "$@"
  # a bare -j is unbounded, which a small CI runner answers with an OOM kill
  cmake --build "$dir" --target llama ggml --parallel "$JOBS"
}

# the archives of VARIANT's build, and what its backend links against
llama_libs() {
  d=$L/build-$1
  libs="$d/src/libllama.a $d/ggml/src/libggml.a $d/ggml/src/libggml-cpu.a $d/ggml/src/libggml-base.a"
  case "$1" in
    metal)  libs="$libs $d/ggml/src/ggml-metal/libggml-metal.a" ;;
    vulkan) libs="$libs $d/ggml/src/ggml-vulkan/libggml-vulkan.a" ;;
    cuda)   libs="$libs $d/ggml/src/ggml-cuda/libggml-cuda.a" ;;
    hip)    libs="$libs $d/ggml/src/ggml-hip/libggml-hip.a" ;;
  esac
  echo "$libs"
}
backend_deps() {
  case "$1" in
    metal)  echo "-framework Metal -framework Foundation -framework MetalKit" ;;
    vulkan) if [ -n "$VULKAN_SDK" ]; then echo "-L$VULKAN_SDK/lib -lvulkan"
            else pkg-config --libs vulkan 2>/dev/null || echo "-lvulkan"; fi ;;
    cuda)   c=${CUDA_PATH:-/usr/local/cuda}; echo "-L$c/lib64 -L$c/lib64/stubs -lcudart -lcublas -lcublasLt -lcuda" ;;
    hip)    r=${ROCM_PATH:-/opt/rocm}; echo "-L$r/lib -lhipblas -lrocblas -lamdhip64" ;;
  esac
}

INC="-I$L/include -I$L/ggml/include -I$L/vendor -Inative -Inative/lame/install/include"
CXXFLAGS="-O2 -std=c++17 -fPIC -Wall $INC"

# --- the engine linked into newsroom: the CPU, and Metal on macOS ---------------
# on macOS Metal unless asked for none, whatever the plugin is
MAIN=cpu
if [ "$OS" = Darwin ] && [ "$GPU" != none ]; then MAIN=metal; fi
build_llama $MAIN
LIBS="$(llama_libs $MAIN) $LAME"
c++ $CXXFLAGS -c native/s3gen.cpp -o "$B/s3gen.o"
c++ $CXXFLAGS -c native/newsroom_tts.cpp -o "$B/newsroom_tts.o"
OBJS="$B/s3gen.o $B/newsroom_tts.o"
case "$OS" in
  Darwin)
    c++ -dynamiclib $OBJS $LIBS $(backend_deps $MAIN) -o native/libnewsroom_tts.dylib
    libtool -static -o native/libnewsroom_tts.a $OBJS $LIBS
    # ld64 links a framework by its stub when it finds lib<Name>.tbd in an -L
    # dir, which is what deps.edn's :static {:lib ...} entries can say
    sdk=$(xcrun --show-sdk-path)
    mkdir -p native/frameworks
    for f in Metal Foundation MetalKit; do
      ln -sf "$sdk/System/Library/Frameworks/$f.framework/$f.tbd" "native/frameworks/lib$f.tbd"
    done
    ;;
  *)
    c++ -shared $OBJS -Wl,--whole-archive $LIBS -Wl,--no-whole-archive -lm -lpthread -o native/libnewsroom_tts.so
    printf 'create native/libnewsroom_tts.a\n' > "$B/tts.mri"
    for o in $OBJS; do printf 'addmod %s\n' "$o" >> "$B/tts.mri"; done
    for a in $LIBS; do printf 'addlib %s\n' "$a" >> "$B/tts.mri"; done
    printf 'save\nend\n' >> "$B/tts.mri"
    ar -M < "$B/tts.mri"
    ;;
esac

# --- the GPU plugin ------------------------------------------------------------
case "$GPU" in
  cuda|hip|vulkan)
    build_llama "$GPU"
    PLIBS="$(llama_libs "$GPU") $LAME"
    c++ $CXXFLAGS -include native/plugin_names.h -c native/s3gen.cpp -o "$B/s3gen-gpu.o"
    c++ $CXXFLAGS -include native/plugin_names.h -c native/newsroom_tts.cpp -o "$B/newsroom_tts-gpu.o"
    if [ "$OS" = Darwin ]; then
      c++ -dynamiclib "$B/s3gen-gpu.o" "$B/newsroom_tts-gpu.o" $PLIBS $(backend_deps "$GPU") \
        -Wl,-exported_symbols_list,native/plugin.exports -o native/libnewsroom_tts_gpu.dylib
    else
      c++ -shared "$B/s3gen-gpu.o" "$B/newsroom_tts-gpu.o" -Wl,--whole-archive $PLIBS -Wl,--no-whole-archive \
        $(backend_deps "$GPU") -lm -lpthread -Wl,--version-script=native/plugin.map -Wl,-Bsymbolic \
        -o native/libnewsroom_tts_gpu.so
    fi
    echo "built the $GPU plugin"
    ;;
esac

c++ $CXXFLAGS native/test_s3gen.cpp "$B/s3gen.o" $LIBS $(backend_deps $MAIN) -lpthread -o "$B/test-s3gen"
c++ $CXXFLAGS native/test_tts.cpp $OBJS $LIBS $(backend_deps $MAIN) -lpthread -o "$B/test-tts"
echo "built: native/libnewsroom_tts.* ($MAIN) and the tests in $B"
