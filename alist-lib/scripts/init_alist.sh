#!/bin/bash

GIT_REPO="https://github.com/OpenListTeam/OpenList.git"

# 支持通过位置参数指定要拉取的分支/标签（如 beta）；不传则取最新版本 tag
REF_NAME="${1:-}"
if [ -n "$REF_NAME" ]; then
  TAG_NAME="$REF_NAME"
else
  TAG_NAME=$(git -c 'versionsort.suffix=-' ls-remote --exit-code --refs --sort='version:refname' --tags $GIT_REPO | tail --lines=1 | cut --delimiter='/' --fields=3)
fi

echo "OpenList - ${TAG_NAME}"
rm -rf ./src
unset GIT_WORK_TREE
# --depth 1：CI 只需要 beta 分支当前快照，浅克隆省掉全历史下载
git clone --depth 1 --branch "$TAG_NAME" https://github.com/OpenListTeam/OpenList.git ./src
rm -rf ./src/.git

mv -f ./src/* ../
rm -rf ./src

cd ../
