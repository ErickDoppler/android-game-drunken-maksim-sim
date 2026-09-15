#!/bin/sh
#
# Builds the Drunken Maksim Sim APK.
#
# Usage:  ./build.sh [debug|release] [--install] [--clean]
#
#   debug     (default) APK signed with the local debug key - installable
#   release   unsigned APK; sign it yourself before installing
#   --install adb install -r the APK onto the single attached device
#   --clean   run a clean build
#
# The toolchain is resolved in this order:
#   1. ./tools/jdk and ./tools/android-sdk  (put there by download-tools.sh)
#   2. $JAVA_HOME / $ANDROID_HOME (or $ANDROID_SDK_ROOT)
#   3. whatever local.properties and the system java already point at
#
# Run ./download-tools.sh first if you have none of those.
#
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

BUILD_TYPE=debug
DO_INSTALL=0
DO_CLEAN=0

for arg in "$@"; do
    case "$arg" in
        debug|release) BUILD_TYPE=$arg ;;
        --install)     DO_INSTALL=1 ;;
        --clean)       DO_CLEAN=1 ;;
        -h|--help)     awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next }
                            NR>1 { exit }' "$0"; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2
           echo "Usage: $0 [debug|release] [--install] [--clean]" >&2
           exit 2 ;;
    esac
done

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;;
    *) IS_WINDOWS=0 ;;
esac

winpath() {
    if [ "$IS_WINDOWS" = 1 ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

propspath() {
    # Path for local.properties: a Windows drive path with forward slashes,
    # so it needs no escaping and the Windows JVM can still resolve it.
    if [ "$IS_WINDOWS" = 1 ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

# ------------------------------------------------------------------- toolchain

JDK=""
if [ -x "$ROOT/tools/jdk/bin/java" ] || [ -x "$ROOT/tools/jdk/bin/java.exe" ]; then
    JDK="$ROOT/tools/jdk"
elif [ -n "${JAVA_HOME:-}" ] && { [ -x "$JAVA_HOME/bin/java" ] || [ -x "$JAVA_HOME/bin/java.exe" ]; }; then
    JDK="$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
    echo "No JDK in ./tools and no JAVA_HOME - falling back to the system java."
else
    echo "No JDK found. Run ./download-tools.sh first." >&2
    exit 1
fi

SDK=""
if [ -d "$ROOT/tools/android-sdk/platforms" ]; then
    SDK="$ROOT/tools/android-sdk"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then
    SDK="$ANDROID_SDK_ROOT"
elif [ ! -f "$ROOT/local.properties" ]; then
    echo "No Android SDK found and no local.properties. Run ./download-tools.sh first." >&2
    exit 1
fi

# local.properties is machine-local and git-ignored; keep it in step with
# whichever SDK we just resolved.
if [ -n "$SDK" ]; then
    printf 'sdk.dir=%s\n' "$(propspath "$SDK")" > "$ROOT/local.properties"
fi

GRADLE_ARGS=""
if [ -n "$JDK" ]; then
    JAVA_HOME=$(winpath "$JDK")
    export JAVA_HOME
    GRADLE_ARGS="-Dorg.gradle.java.home=$JAVA_HOME"
fi

# ----------------------------------------------------------------------- build

case "$BUILD_TYPE" in
    debug)   TASK=assembleDebug
             APK="app/build/outputs/apk/debug/app-debug.apk"
             OUT="dist/drunken-maksim-sim-debug.apk" ;;
    release) TASK=assembleRelease
             APK="app/build/outputs/apk/release/app-release-unsigned.apk"
             OUT="dist/drunken-maksim-sim-release-unsigned.apk" ;;
esac

chmod +x ./gradlew 2>/dev/null || true

if [ "$DO_CLEAN" = 1 ]; then
    echo "Cleaning..."
    # shellcheck disable=SC2086
    ./gradlew $GRADLE_ARGS clean
fi

echo "Building $BUILD_TYPE..."
# shellcheck disable=SC2086
./gradlew $GRADLE_ARGS "$TASK"

if [ ! -f "$APK" ]; then
    echo "Expected APK not found at $APK" >&2
    exit 1
fi

mkdir -p dist
cp "$APK" "$OUT"

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
if [ "$BUILD_TYPE" = release ]; then
    echo "NOTE: this release APK is UNSIGNED - sign it with apksigner before installing."
fi

# --------------------------------------------------------------------- install

if [ "$DO_INSTALL" = 1 ]; then
    ADB=adb
    if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then
        ADB="$SDK/platform-tools/adb"
    elif [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb.exe" ]; then
        ADB="$SDK/platform-tools/adb.exe"
    elif ! command -v adb >/dev/null 2>&1; then
        echo "adb not found - skipping install." >&2
        exit 1
    fi
    echo
    echo "Installing on the attached device..."
    "$ADB" install -r "$OUT"
fi
