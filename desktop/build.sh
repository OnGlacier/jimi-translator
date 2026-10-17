#!/bin/sh
# 构建纯 Java 桌面版（Windows / Linux 通用）：javac + jar，无第三方依赖。
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
OUT="$ROOT/build"
SRC="$ROOT/src"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

find "$SRC" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -d "$OUT/classes" @"$OUT/sources.txt"

mkdir -p "$OUT/classes/META-INF"
cat > "$OUT/classes/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Main-Class: com.jimi.desktop.Main
EOF

jar --create --file "$OUT/jimi-translator.jar" --manifest "$OUT/classes/META-INF/MANIFEST.MF" \
  -C "$OUT/classes" .
echo "BUILD_OK -> $OUT/jimi-translator.jar"
