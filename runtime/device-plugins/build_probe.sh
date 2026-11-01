#!/bin/sh
# Build only the isolated probe APK and production worker; never downloads build tools.
set -eu
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed SDK}"
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
output="$repo/runtime/device-plugins/build/screen-probe"
mkdir -p "$output/classes" "$output/dex" "$output/app-classes" "$output/app-dex"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
tools="$ANDROID_HOME/build-tools/35.0.0"
"$JAVA_HOME/bin/javac" --release 17 -cp "$platform" -d "$output/classes" \
    "$repo"/runtime/device-plugins/src/main/java/com/github/ytlog/mobby/android/device/privileged/*.java \
    "$repo/runtime/device-plugins/src/androidTest/java/com/github/ytlog/mobby/android/device/privileged/ScreenProbeMain.java"
"$JAVA_HOME/bin/jar" --create --file "$output/classes.jar" -C "$output/classes" .
"$tools/d8" --lib "$platform" --min-api 33 --output "$output/dex" "$output/classes.jar"
"$JAVA_HOME/bin/jar" --create --file "$output/worker.jar" -C "$output/dex" .
"$JAVA_HOME/bin/javac" --release 17 -cp "$platform" -d "$output/app-classes" "$repo/runtime/device-plugins/probe/dev/mobby/screenprobe/ProbeActivity.java"
"$JAVA_HOME/bin/jar" --create --file "$output/app-classes.jar" -C "$output/app-classes" .
"$tools/d8" --lib "$platform" --min-api 33 --output "$output/app-dex" "$output/app-classes.jar"
"$tools/aapt2" link --manifest "$repo/runtime/device-plugins/probe/AndroidManifest.xml" -I "$platform" -o "$output/probe-unsigned.apk"
python3 - "$output" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'probe-unsigned.apk','a') as z: z.write(p/'app-dex/classes.dex','classes.dex')
PY
if [ ! -f "$output/probe.keystore" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$output/probe.keystore" -storepass android -keypass android -alias probe -keyalg RSA -validity 3650 -dname 'CN=mobby disposable test probe'
fi
"$tools/apksigner" sign --ks "$output/probe.keystore" --ks-pass pass:android --key-pass pass:android --out "$output/probe.apk" "$output/probe-unsigned.apk"
echo "$output"
