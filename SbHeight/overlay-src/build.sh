#!/bin/bash
# Builds one overlay APK per height and places them in the module's
# system/product/overlay folder. Runs on GitHub Actions (needs Android SDK).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$(dirname "$HERE")
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
echo "build-tools: $BT"; echo "android.jar: $JAR"
WORK=$(mktemp -d)
KS="$WORK/key.jks"
keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias sbheight -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=SbHeight" >/dev/null 2>&1
rm -rf "$MOD/system"
for H in $(cat "$HERE/heights"); do
  PKG="com.dmk.sbheight.h$H"
  D="$WORK/h$H"; mkdir -p "$D/res/values"
  cat > "$D/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
    <overlay android:targetPackage="android" android:priority="999" android:isStatic="false" />
    <application android:hasCode="false" android:label="SbHeight $H" />
</manifest>
XML
  cat > "$D/res/values/dimens.xml" <<XML
<resources>
    <dimen name="status_bar_height_default">${H}dp</dimen>
    <dimen name="status_bar_height_portrait">${H}dp</dimen>
    <dimen name="status_bar_height_landscape">${H}dp</dimen>
</resources>
XML
  "$BT/aapt2" compile --dir "$D/res" -o "$D/res.zip"
  "$BT/aapt2" link -I "$JAR" --manifest "$D/AndroidManifest.xml" \
      -o "$D/unsigned.apk" "$D/res.zip" --auto-add-overlay
  "$BT/zipalign" -f 4 "$D/unsigned.apk" "$D/aligned.apk"
  OUT="$MOD/system/product/overlay/SbHeight$H"
  mkdir -p "$OUT"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
      --out "$OUT/SbHeight$H.apk" "$D/aligned.apk"
  echo "built $OUT/SbHeight$H.apk"
done
