#!/bin/sh
# 用 Android SDK 的命令行工具手搓 APK（不依赖 gradle）。
set -e

SDK=${ANDROID_HOME:-/opt/android-sdk}
BT="$SDK/build-tools/34.0.0"
QEMU=/opt/qemu/usr/bin/qemu-x86_64-static
X86ROOT=/opt/x86
AAPT2="$QEMU -L $X86ROOT $BT/aapt2"
ZIPALIGN="$QEMU -L $X86ROOT $BT/zipalign"
JAR="$SDK/platforms/android-35/android.jar"
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

if [ ! -f "$JAR" ]; then echo "android.jar 不存在: $JAR" >&2; exit 1; fi

rm -rf out
mkdir -p out/gen out/classes out/dex

echo "== aapt2 compile"
$AAPT2 compile --dir res -o out/res.zip

echo "== aapt2 link"
$AAPT2 link \
  -o out/base.apk \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  --java out/gen \
  --auto-add-overlay \
  out/res.zip

echo "== javac"
find src out/gen -name '*.java' > out/sources.txt
javac -J-Xmx420m -source 8 -target 8 -encoding UTF-8 -nowarn \
  -classpath "$JAR" \
  -d out/classes @out/sources.txt

echo "== d8"
find out/classes -name '*.class' > out/classes.txt
"$BT/d8" --lib "$JAR" --min-api 30 --output out/dex \
  $(cat out/classes.txt | tr '\n' ' ')

echo "== package"
cp out/base.apk out/unsigned.apk
cd out
python3 - <<'PY'
import zipfile, shutil, os
src = 'unsigned.apk'
tmp = 'unsigned2.apk'
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write('dex/classes.dex', 'classes.dex')
os.replace(tmp, src)
print('dex added')
PY
cd "$HERE"

echo "== zipalign"
$ZIPALIGN -f -p 4 out/unsigned.apk out/aligned.apk

echo "== sign"
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -alias rltr -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass rltranslate -keypass rltranslate \
    -dname "CN=RL Translator, OU=Personal, O=Personal, L=Local, S=Local, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:rltranslate --key-pass pass:rltranslate \
  --out out/app.apk out/aligned.apk

ls -la out/app.apk
echo "BUILD_OK"
