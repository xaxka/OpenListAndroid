#!/usr/bin/env bash
# 在 EasyTier 仓库根目录执行：为指定 Android ABI 构建 libeasytier_android_jni.so。
#
# 用法: build-easytier-jni.sh <android-abi> <rust-target> <output-dir>
#   例: build-easytier-jni.sh arm64-v8a aarch64-linux-android /path/to/jni-out
#
# 前置环境（由 CI 工作流准备）：
#   - Rust 1.95 + 对应 NDK 交叉编译 target
#   - cargo-ndk
#   - ANDROID_NDK_HOME 指向 Android NDK（r26）
#   - protoc（easytier-proto build.rs 需要）
#
# 产物: <output-dir>/<android-abi>/libeasytier_android_jni.so
#       （若 JNI 库动态依赖 libeasytier_ffi.so 则一并附上，当前版本为静态链接无需附带）

set -euo pipefail

ABI="${1:?android-abi required, e.g. arm64-v8a}"
RUST_TARGET="${2:?rust-target required, e.g. aarch64-linux-android}"
OUT_DIR="${3:?output dir required}"

echo "==> Build easytier-android-jni for $ABI ($RUST_TARGET)"
echo "    ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-<unset>}"
echo "    rustc: $(rustc --version), cargo-ndk: $(cargo ndk --version 2>/dev/null || echo '<missing>')"

# -p 24：NDK API level 24（与 EasyTier 官方 Android GUI 一致；宿主 App minSdk 21 上
# 库加载会优雅降级为「不可用」，不影响 OpenList 主功能）
cargo ndk -t "$ABI" -p 24 build --release --package easytier-android-jni

SO="target/$RUST_TARGET/release/libeasytier_android_jni.so"
if [ ! -f "$SO" ]; then
  echo "ERROR: $SO not produced" >&2
  exit 1
fi

mkdir -p "$OUT_DIR/$ABI"
cp "$SO" "$OUT_DIR/$ABI/"

# 防御性检查：若 .so 存在对 libeasytier_ffi.so 的动态依赖则一并打包
if command -v readelf >/dev/null 2>&1; then
  echo "==> DT_NEEDED of $SO:"
  readelf -d "$SO" | grep NEEDED || true
  if readelf -d "$SO" 2>/dev/null | grep -q "libeasytier_ffi.so"; then
    FFI="target/$RUST_TARGET/release/libeasytier_ffi.so"
    if [ -f "$FFI" ]; then
      cp "$FFI" "$OUT_DIR/$ABI/"
      echo "==> Bundled libeasytier_ffi.so (dynamic dependency)"
    else
      echo "WARN: libeasytier_ffi.so required but missing" >&2
    fi
  fi
fi

ls -lh "$OUT_DIR/$ABI"
