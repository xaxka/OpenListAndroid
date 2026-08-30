#!/usr/bin/env bash
#
# qbittorrent-enhanced-nox —— Android (bionic) 动态链接版交叉编译
#
# 背景：上游 c0re100 的 musl 静态二进制在 Android 上 DNS 全灭（musl resolver 硬编码
# /etc/resolv.conf，缺失时回退 127.0.0.1:53 无监听），且 App 内 SOCKS5 代理无
# UDP ASSOCIATE，DHT（UDP）流量被 libtorrent 交给代理后全部丢弃。
#
# 本脚本改用 NDK 工具链链接 bionic：getaddrinfo → netd（继承系统 Private DNS /
# DNS64 / VPN DNS），DHT/peer/tracker 全部直连，DNS 与 DHT 双双根治。
# 依赖链与上游 cross_build.sh 对齐（Qt6 静态 + openssl-linked + libtorrent RC_1_2 +
# Boost 纯头文件 + zlib-ng(compat)），仅把 musl 静态换成 bionic 动态：
# 产物是 PIE 可执行文件，动态依赖为 bionic 系统库 + libc++_shared.so
# （Qt 在 Android 强制 c++_shared；该 .so 随产物一并输出，由 App 经
# LD_LIBRARY_PATH=nativeLibraryDir 提供给子进程）。
#
# 用法（由 .github/workflows/build.yml 调用）：
#   bash build-qbt-nox-bionic.sh <ABI> <OPENSSL_TARGET> <OUT_DIR> <PREFIX_DIR>
# 例：
#   bash build-qbt-nox-bionic.sh arm64-v8a android-arm64 \
#        "$GITHUB_WORKSPACE/qbt-out" "$GITHUB_WORKSPACE/qbt-prefix"
#
# 环境变量（可选覆盖）：
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT  NDK 路径（必填其一）
#   ANDROID_PLATFORM    默认 android-24（Qt 6.8 最低支持 API 24）
#   QT_VER / OPENSSL_VER / BOOST_VER / ZLIB_NG_VER
#   QBT_REF / LT_REF    固定 commit SHA（默认与上游 release-5.2.3.10 配方一致）
#   NDK_CCACHE          如 "ccache" 则启用编译缓存（NDK 工具链原生支持）
#
# 各阶段幂等（以安装产物为标记），配合 actions/cache 可断点续跑。

set -euo pipefail

ABI="${1:?usage: build-qbt-nox-bionic.sh <ABI> <OPENSSL_TARGET> <OUT_DIR> <PREFIX_DIR>}"
OPENSSL_TARGET="${2:?missing OPENSSL_TARGET}"
OUT_DIR="${3:?missing OUT_DIR}"
PREFIX_DIR="${4:?missing PREFIX_DIR}"

QT_VER="${QT_VER:-6.8.3}"
OPENSSL_VER="${OPENSSL_VER:-3.5.1}"
BOOST_VER="${BOOST_VER:-1.86.0}"
ZLIB_NG_VER="${ZLIB_NG_VER:-2.3.3}"
QBT_REPO="${QBT_REPO:-https://github.com/c0re100/qBittorrent-Enhanced-Edition.git}"
# release-5.2.3.10（与 App QBittorrentSpec.EMBEDDED_VERSION 一致）
QBT_REF="${QBT_REF:-44ee266a575600d04788623b6939e47443d27ed1}"
LT_REPO="${LT_REPO:-https://github.com/arvidn/libtorrent.git}"
# 上游 cross_build.sh 的 LIBTORRENT_BRANCH=RC_1_2 固定到具体 commit
LT_REF="${LT_REF:-c5ff6c3186a92ddec01f6f0a8146aaedb4a1c3f9}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "ERROR: ANDROID_NDK_HOME/ANDROID_NDK_ROOT 未设置或不存在: $NDK" >&2
  exit 1
fi
TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
NDK_HOST_PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API_LEVEL="${ANDROID_PLATFORM#android-}"
# qtbase 的 QtPlatformAndroid.cmake 要求 ANDROID_SDK_ROOT 作为 CMake 变量（无环境变量回退），
# 且目录必须存在（jar 缺失仅告警）；默认取 GH runner 预装路径。
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"

JOBS="$(nproc)"
[ "$JOBS" -gt 2 ] || JOBS=2

# 源码/下载/构建树放在 prefix 的同名 .build 目录（不进 actions/cache，每轮重建）
BUILD_ROOT="$PREFIX_DIR.build"
SRC_DIR="$BUILD_ROOT/src"
DL_DIR="$BUILD_ROOT/downloads"
WORK_DIR="$BUILD_ROOT/work"
HOST_QT="$PREFIX_DIR/qt-host/$QT_VER/gcc_64"   # aqt 预编译桌面版（host 工具）
QT_PREFIX="$PREFIX_DIR/qt"         # 交叉编译安装的静态 Qt

# 阶段完成标记：仅用于「版本已由 prefix 缓存 key 锁定」的阶段（zlib-ng/OpenSSL/
# Boost/Qt host/Qt android）。libtorrent 与 qBt 的 ref 不在 prefix 缓存 key 内，
# 永不标记、每次重建，避免换 ref 后用到陈旧产物。
stage_done() { [ -f "$PREFIX_DIR/.stage-$1" ] && { log "$1 已完成（缓存命中），跳过"; return 0; } || return 1; }
mark_done()  { touch "$PREFIX_DIR/.stage-$1"; }

mkdir -p "$OUT_DIR" "$PREFIX_DIR" "$SRC_DIR" "$DL_DIR" "$WORK_DIR"

# 动态依赖白名单：bionic 系统库 + libc++_shared.so（Qt 在 Android 强制 c++_shared，
# 该库随产物输出并由 App 在拉起子进程时经 LD_LIBRARY_PATH 提供）
ALLOWED_NEEDED='^(libc\.so|libm\.so|libdl\.so|liblog\.so|libandroid\.so|libc\+\+_shared\.so)$'

log() { printf '\n========== %s ==========\n' "$*" >&2; }
fetch() {  # fetch <url> <dest>
  curl -fSL --retry 5 --retry-delay 3 --connect-timeout 20 "$1" -o "$2"
}

cmake_common=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="$ANDROID_PLATFORM"
  -DANDROID_STL=c++_shared
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_INSTALL_PREFIX="$PREFIX_DIR"
)

# NDK 工具链的 ccache 接入：必须作为 CMake 变量传递（-DNDK_CCACHE），环境变量不生效
NDK_CCACHE_ARGS=()
if command -v ccache >/dev/null 2>&1; then
  NDK_CCACHE_ARGS=(-DNDK_CCACHE=ccache)
  cmake_common+=("${NDK_CCACHE_ARGS[@]}")
fi

log "环境：ABI=$ABI OPENSSL_TARGET=$OPENSSL_TARGET API=$API_LEVEL"
log "版本：Qt=$QT_VER OpenSSL=$OPENSSL_VER Boost=$BOOST_VER zlib-ng=$ZLIB_NG_VER"
log "refs：qBt=$QBT_REF libtorrent=$LT_REF"
log "NDK=$NDK  jobs=$JOBS"
df -h "$PREFIX_DIR" >&2 || true

# ---------------------------------------------------------------- zlib-ng
build_zlib_ng() {
  stage_done zlib-ng && return
  log "构建 zlib-ng $ZLIB_NG_VER（ZLIB_COMPAT）"
  local src="$SRC_DIR/zlib-ng-$ZLIB_NG_VER"
  if [ ! -d "$src" ]; then
    fetch "https://github.com/zlib-ng/zlib-ng/archive/refs/tags/$ZLIB_NG_VER.tar.gz" \
      "$DL_DIR/zlib-ng-$ZLIB_NG_VER.tar.gz"
    mkdir -p "$src"
    tar -xzf "$DL_DIR/zlib-ng-$ZLIB_NG_VER.tar.gz" --strip-components=1 -C "$src"
  fi
  rm -rf "$WORK_DIR/zlib-ng"
  cmake -S "$src" -B "$WORK_DIR/zlib-ng" "${cmake_common[@]}" \
    -DBUILD_SHARED_LIBS=OFF -DZLIB_COMPAT=ON -DWITH_GTEST=OFF
  cmake --build "$WORK_DIR/zlib-ng" --parallel "$JOBS"
  cmake --install "$WORK_DIR/zlib-ng"
  rm -rf "$WORK_DIR/zlib-ng"
  mark_done zlib-ng
}

# ---------------------------------------------------------------- OpenSSL
build_openssl() {
  stage_done openssl && return
  log "构建 OpenSSL $OPENSSL_VER（$OPENSSL_TARGET 静态）"
  local src="$SRC_DIR/openssl-$OPENSSL_VER"
  if [ ! -d "$src" ]; then
    fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz" \
      "$DL_DIR/openssl-$OPENSSL_VER.tar.gz"
    mkdir -p "$src"
    tar -xzf "$DL_DIR/openssl-$OPENSSL_VER.tar.gz" --strip-components=1 -C "$src"
  fi
  (
    cd "$src"
    export PATH="$NDK_HOST_PREBUILT/bin:$PATH"
    ./Configure "$OPENSSL_TARGET" \
      -static -fPIC no-tests no-shared no-docs \
      -D__ANDROID_API__="$API_LEVEL" \
      --prefix="$PREFIX_DIR"
    make -j"$JOBS" >/dev/null
    make install_sw
  )
  mark_done openssl
}

# ---------------------------------------------------------------- Boost（纯头文件）
install_boost_headers() {
  stage_done boost && return
  log "安装 Boost $BOOST_VER 头文件"
  local src="$SRC_DIR/boost-$BOOST_VER"
  if [ ! -d "$src" ]; then
    local dest="$DL_DIR/boost-$BOOST_VER.tar.bz2"
    fetch "https://archives.boost.io/release/$BOOST_VER/source/boost_${BOOST_VER//./_}.tar.bz2" "$dest" ||
      fetch "https://sourceforge.net/projects/boost/files/boost/$BOOST_VER/boost_${BOOST_VER//./_}.tar.bz2/download" "$dest"
    mkdir -p "$src"
    tar -xjf "$dest" --strip-components=1 -C "$src"
  fi
  mkdir -p "$PREFIX_DIR/include"
  rm -rf "$PREFIX_DIR/include/boost"
  cp -r "$src/boost" "$PREFIX_DIR/include/"
  mark_done boost
}

# ---------------------------------------------------------------- Qt host 工具（aqt 预编译）
install_qt_host() {
  stage_done qt-host && return
  log "安装 Qt $QT_VER 桌面版 host 工具（aqt）"
  local aqt_bin=""
  if command -v aqt >/dev/null 2>&1; then
    aqt_bin="$(command -v aqt)"
  else
    # 用独立 venv 安装（路径确定；pipx 的 PIPX_BIN_DIR 在 runner 上可能不在 ~/.local/bin）
    local venv="$BUILD_ROOT/aqt-venv"
    python3 -m venv "$venv"
    "$venv/bin/pip" -q install aqtinstall
    aqt_bin="$venv/bin/aqt"
  fi
  echo "aqt: $aqt_bin" >&2
  "$aqt_bin" install-qt -O "$PREFIX_DIR/qt-host" linux desktop "$QT_VER" \
    --archives qtbase qttools icu
  # Qt 6.8 起 host 工具（moc/rcc 等）安装在 libexec/；旧版本在 bin/
  test -x "$HOST_QT/libexec/moc" || test -x "$HOST_QT/bin/moc"
  mark_done qt-host
}

# ---------------------------------------------------------------- Qt（Android 静态）
build_qt_android() {
  stage_done qt-android && return
  log "构建 qtbase $QT_VER（Android $ABI 静态：Core/Network/Sql/Xml）"
  local src="$SRC_DIR/qtbase-$QT_VER"
  if [ ! -d "$src" ]; then
    local major="${QT_VER%.*}"
    fetch "https://download.qt.io/official_releases/qt/$major/$QT_VER/submodules/qtbase-everywhere-src-$QT_VER.tar.xz" \
      "$DL_DIR/qtbase-$QT_VER.tar.xz"
    mkdir -p "$src"
    tar -xJf "$DL_DIR/qtbase-$QT_VER.tar.xz" --strip-components=1 -C "$src"
  fi
  # 用 qtbase 自带 configure 包装脚本（与上游 cross_build.sh 同源）：
  # 特性旗标由脚本翻译成正确的 QT_FEATURE_*，避免手写变量出错；
  # -- 之后是透传给 CMake 的参数（NDK 工具链 + Android 三件套）。
  local bdir="$WORK_DIR/qt"
  rm -rf "$bdir"
  mkdir -p "$bdir"
  (
    cd "$bdir"
    "$src/configure" \
      -prefix "$QT_PREFIX" \
      -qt-host-path "$HOST_QT" \
      -release -static -c++std c++17 \
      -optimize-size \
      -feature-optimize_full \
      -openssl -openssl-linked \
      -no-gui -no-dbus -no-widgets \
      -no-feature-testlib \
      -no-feature-animation \
      -nomake examples -nomake tests \
      -- \
      -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
      -DANDROID_ABI="$ABI" \
      -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
      -DANDROID_STL=c++_shared \
      -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
      -DOPENSSL_ROOT_DIR="$PREFIX_DIR" \
      -DCMAKE_PREFIX_PATH="$PREFIX_DIR" \
      "${NDK_CCACHE_ARGS[@]}"
  )
  cmake --build "$bdir" --parallel "$JOBS"
  cmake --install "$bdir"
  rm -rf "$bdir"
  mark_done qt-android
}

# ---------------------------------------------------------------- libtorrent
build_libtorrent() {
  log "构建 libtorrent-rasterbar（$LT_REF）"
  local src="$SRC_DIR/libtorrent"
  if [ ! -d "$src/.git" ]; then
    git init -q "$src"
    git -C "$src" remote add origin "$LT_REPO"
    git -C "$src" fetch -q --depth 1 origin "$LT_REF"
    git -C "$src" checkout -q FETCH_HEAD
    git -C "$src" log -1 --oneline >&2 || true
  fi
  rm -rf "$WORK_DIR/libtorrent"
  cmake -S "$src" -B "$WORK_DIR/libtorrent" "${cmake_common[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -Dstatic_runtime=ON \
    -DCMAKE_CXX_STANDARD=17 \
    -DCMAKE_PREFIX_PATH="$PREFIX_DIR" \
    -Dbuild_tests=OFF -Dbuild_examples=OFF -Dbuild_tools=OFF -Dpython-bindings=OFF
  cmake --build "$WORK_DIR/libtorrent" --parallel "$JOBS"
  cmake --install "$WORK_DIR/libtorrent"
  rm -rf "$WORK_DIR/libtorrent"
}

# ---------------------------------------------------------------- qbittorrent-nox
build_qbittorrent() {
  log "构建 qbittorrent-enhanced-nox（$QBT_REF）"
  local src="$SRC_DIR/qbt"
  if [ ! -d "$src/.git" ]; then
    git init -q "$src"
    git -C "$src" remote add origin "$QBT_REPO"
    git -C "$src" fetch -q --depth 1 origin "$QBT_REF"
    git -C "$src" checkout -q FETCH_HEAD
    git -C "$src" log -1 --oneline >&2 || true
  fi
  rm -rf "$WORK_DIR/qbt"
  cmake -S "$src" -B "$WORK_DIR/qbt" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    -DGUI=OFF -DSTACKTRACE=OFF -DTESTING=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_INSTALL_PREFIX="$PREFIX_DIR" \
    -DQT_HOST_PATH="$HOST_QT" \
    -DCMAKE_PREFIX_PATH="$PREFIX_DIR;$QT_PREFIX" \
    -DOPENSSL_ROOT_DIR="$PREFIX_DIR" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    "${NDK_CCACHE_ARGS[@]}"
  cmake --build "$WORK_DIR/qbt" --parallel "$JOBS"
  cmake --install "$WORK_DIR/qbt"
  test -f "$PREFIX_DIR/bin/qbittorrent-nox"
  rm -rf "$WORK_DIR/qbt"
}

# ---------------------------------------------------------------- 产物校验与落盘
install_output() {
  local bin="$PREFIX_DIR/bin/qbittorrent-nox"
  local out="$OUT_DIR/$ABI/libqbittorrent-nox.so"
  mkdir -p "$OUT_DIR/$ABI"

  # 捆绑 libc++_shared.so（Qt 强制 c++_shared；App 侧以 LD_LIBRARY_PATH 指向同目录）
  local triple
  case "$ABI" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7a-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;;
  esac
  local stl="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so"
  if [ ! -f "$stl" ]; then
    stl="$(find "$NDK" -type f -name libc++_shared.so -path "*$triple*" | head -1)"
  fi
  test -n "$stl" && test -f "$stl" || { echo "ERROR: libc++_shared.so not found in NDK" >&2; exit 1; }
  install -m 644 "$stl" "$OUT_DIR/$ABI/libc++_shared.so"

  # strip 缩体积（无调试需求的发行形态）
  "$NDK_HOST_PREBUILT/bin/llvm-strip" "$bin"

  echo "---- file ----"
  file "$bin" >&2 || true
  echo "---- ELF header ----"
  "$NDK_HOST_PREBUILT/bin/llvm-readelf" -h "$bin" | grep -E 'Class:|Machine:|Type:' >&2 || true
  echo "---- NEEDED ----"
  "$NDK_HOST_PREBUILT/bin/llvm-readelf" -d "$bin" | grep NEEDED >&2 || echo '(no NEEDED)' >&2

  # 契约校验 1：必须是 PIE（ET_DYN，可经 jniLibs 打包 + nativeLibraryDir exec）
  local etype
  etype="$("$NDK_HOST_PREBUILT/bin/llvm-readelf" -h "$bin" | sed -n 's/.*Type:.*(\(.*\))/\1/p')"
  if [ "$etype" != "DYN (Position-Independent Executable file)" ] && [ "$etype" != "DYN" ]; then
    echo "ERROR: 期望 PIE(ET_DYN)，实际: $etype" >&2
    exit 1
  fi

  # 契约校验 2：动态依赖只能是 bionic 系统库 + libc++_shared.so（出现 Qt/ssl 等即失败）
  local bad=""
  bad="$("$NDK_HOST_PREBUILT/bin/llvm-readelf" -d "$bin" \
    | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' \
    | grep -Ev "$ALLOWED_NEEDED" || true)"
  if [ -n "$bad" ]; then
    echo "ERROR: 存在非 bionic 系统库依赖：$bad" >&2
    exit 1
  fi

  # 信息项：getaddrinfo 应以动态符号导入（bionic → netd 的 DNS 通路）
  if "$NDK_HOST_PREBUILT/bin/llvm-readelf" --dyn-syms "$bin" | grep -qw getaddrinfo; then
    echo "getaddrinfo: 动态导入 ✓（DNS 走 bionic/netd）" >&2
  else
    echo "WARN: 未发现 getaddrinfo 动态导入（QtNetwork 可能内联封装，请人工确认）" >&2
  fi

  install -m 755 "$bin" "$out"
  sha256sum "$out" >&2
  ls -l "$out" >&2
}

build_zlib_ng
build_openssl
install_boost_headers
install_qt_host
build_qt_android
build_libtorrent
build_qbittorrent
install_output

log "完成：$OUT_DIR/$ABI/libqbittorrent-nox.so"
