#!/bin/bash

GIT_REPO="https://github.com/OpenListTeam/OpenList-Frontend.git"
LATEST_TAG=$(git -c 'versionsort.suffix=-' ls-remote --exit-code --refs --sort='version:refname' --tags $GIT_REPO | tail --lines=1 | cut --delimiter='/' --fields=3)

echo "Frontend - ${LATEST_TAG}"
curl -L https://github.com/OpenListTeam/OpenList-Frontend/releases/download/${LATEST_TAG}/openlist-frontend-dist-${LATEST_TAG}.tar.gz -o dist.tar.gz
rm -rf ../public/dist
mkdir -p ../public/dist
tar -zxvf dist.tar.gz -C ../public/dist
rm -rf dist.tar.gz