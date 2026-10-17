#!/bin/sh
# 手搓 APK：aapt2 + javac + d8 + apksigner，不依赖 Gradle。
#
# 用法：
#   export ANDROID_HOME=/path/to/android-sdk
#   bash scripts/build-apk.sh
#
# 需要：JDK 17、Android SDK（platform 35 + build-tools 34+）、python3。
# 在 arm64 Linux 上（例如 Android 手机里的容器），官方 aapt2/zipalign 是 x86_64
# 二进制，需要 qemu-user-static；脚本会自动检测并套上 qemu。
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-35/android.jar"
APP="$ROOT/app/src/main"
OUT="$ROOT/build/out"
KEYSTORE=${JIMI_KEYSTORE:-$ROOT/keystore.jks}
STOREPASS=${JIMI_STOREPASS:-rltranslate}
KEYPASS=${JIMI_KEYPASS:-rltranslate}

[ -f "$JAR" ] || { echo "找不到 android.jar：$JAR（请设置 ANDROID_HOME）" >&2; exit 1; }
[ -d "$BT" ] || { echo "找不到 build-tools：$BT" >&2; exit 1; }

# arm64 主机上，x86_64 的 SDK 工具需要 qemu 前缀
QEMU=${JIMI_QEMU:-/opt/qemu/usr/bin/qemu-x86_64-static}
X86ROOT=${JIMI_X86ROOT:-/opt/x86}
PREFIX=""
if [ "$(uname -m)" = "aarch64" ] && [ -x "$QEMU" ] && [ -d "$X86ROOT" ]; then
  PREFIX="$QEMU -L $X86ROOT"
fi
AAPT2="$PREFIX $BT/aapt2"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile"
$AAPT2 compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== aapt2 link"
# 允许用环境变量覆盖版本号，CI 打 tag 时直接对齐（避免 APK 里还写着旧版本）
VERSION_FLAGS=""
[ -n "$JIMI_VERSION_CODE" ] && VERSION_FLAGS="$VERSION_FLAGS --version-code $JIMI_VERSION_CODE"
[ -n "$JIMI_VERSION_NAME" ] && VERSION_FLAGS="$VERSION_FLAGS --version-name $JIMI_VERSION_NAME"
$AAPT2 link -o "$OUT/base.apk" -I "$JAR" $VERSION_FLAGS \
  --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" "$OUT/res.zip"

echo "== javac"
find "$APP/java" -name '*.java' > "$OUT/sources.txt"
[ -d "$OUT/gen" ] && find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt" || true
javac -J-Xmx192m -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 -source 8 -target 8 -encoding UTF-8 -nowarn \
  -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$JAR" --min-api 30 --output "$OUT/dex" $(tr '\n' ' ' < "$OUT/classes.txt")

echo "== package"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, struct
base, dex, dest = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as zout:
    for old in zin.infolist():
        data = zin.read(old.filename)
        info = zipfile.ZipInfo(old.filename, date_time=old.date_time)
        info.external_attr = old.external_attr
        info.compress_type = old.compress_type
        pos = zout.fp.tell()
        start = pos + 30 + len(old.filename.encode('utf-8'))
        if old.compress_type == zipfile.ZIP_STORED:
            pad = (4 - (start % 4)) % 4
            info.extra = struct.pack('<HH', 0xD935, pad) + b'\x00' * pad
        zout.writestr(info, data)
    info = zipfile.ZipInfo('classes.dex')
    info.compress_type = zipfile.ZIP_DEFLATED
    zout.writestr(info, open(dex, 'rb').read())
print('dex 已并入 APK')
PY

echo "== sign"
if [ ! -f "$KEYSTORE" ]; then
  echo "生成新签名（仅首次；换钥匙会导致无法覆盖升级）"
  keytool -genkeypair -keystore "$KEYSTORE" -alias jimi -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$STOREPASS" -keypass "$KEYPASS" \
    -dname "CN=Jimi Translator, OU=Personal, O=Personal, L=Local, S=Local, C=CN" >/dev/null 2>&1
fi
java -Xint -Xmx96m -jar "$BT/lib/apksigner.jar" sign \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false \
  --min-sdk-version 30 \
  --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
  --out "$ROOT/build/app.apk" "$OUT/unsigned.apk"

ls -la "$ROOT/build/app.apk"
echo "BUILD_OK  →  $ROOT/build/app.apk"
