#!/bin/bash

cd ../alistlib || exit
# -trimpath 经 GOFLAGS 透传给 go build，去除绝对路径；-s -w 去符号表与 DWARF
# release 限定与 app 分发 ABI 一致的 3 个 target（不带 -target 默认会额外编译 x86）
if [ "$1" == "debug" ]; then
  GOFLAGS="-trimpath" gomobile bind -ldflags "-s -w" -v -androidapi 19 -target="android/arm64"
else
  GOFLAGS="-trimpath" gomobile bind -ldflags "-s -w" -v -androidapi 19 -target="android/arm64,android/arm,android/amd64"
fi

echo "Moving aar and jar files to android/app/libs"
mkdir -p ../../android/app/libs
mv -f ./*.aar ../../android/app/libs
mv -f ./*.jar ../../android/app/libs
