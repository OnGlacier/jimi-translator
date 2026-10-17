#!/bin/sh
# 启动桌面版。先跑 build.sh 生成 jar。
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
JAR="$ROOT/build/jimi-translator.jar"
[ -f "$JAR" ] || { echo "还没构建：先运行 ./build.sh" >&2; exit 1; }
exec java -jar "$JAR" "$@"
