#!/bin/sh
# Build the speech engine, native/newsroom_tts.cpp and native/s3gen.cpp, against
# llama.cpp (native/llama.cpp, fetched and built by the `jolt tts` task at the
# pinned commit) and LAME (native/lame, built here from its release tarball),
# into libnewsroom_tts.{dylib,so} for jolt serve and test, and
# libnewsroom_tts.a, every member inside, for jolt build. Also builds
# native/build/test-s3gen and test-tts, the decoder's and the engine's
# parity tests. Run from the project root.
set -e
L=native/llama.cpp
B=native/build
mkdir -p "$B"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)

# LAME, static and position independent
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

INC="-I$L/include -I$L/ggml/include -I$L/vendor -Inative -Inative/lame/install/include"
CXXFLAGS="-O2 -std=c++17 -fPIC -Wall $INC"
LIBS="$L/build/src/libllama.a $L/build/ggml/src/libggml.a $L/build/ggml/src/libggml-cpu.a
      $L/build/ggml/src/libggml-base.a native/lame/install/lib/libmp3lame.a"

c++ $CXXFLAGS -c native/s3gen.cpp -o "$B/s3gen.o"
c++ $CXXFLAGS -c native/newsroom_tts.cpp -o "$B/newsroom_tts.o"
OBJS="$B/s3gen.o $B/newsroom_tts.o"

case "$(uname -s)" in
  Darwin)
    c++ -dynamiclib $OBJS $LIBS -o native/libnewsroom_tts.dylib
    libtool -static -o native/libnewsroom_tts.a $OBJS $LIBS
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

c++ $CXXFLAGS native/test_s3gen.cpp "$B/s3gen.o" $LIBS -lpthread -o "$B/test-s3gen"
c++ $CXXFLAGS native/test_tts.cpp $OBJS $LIBS -lpthread -o "$B/test-tts"
echo "built: native/libnewsroom_tts.* and the tests in $B"
