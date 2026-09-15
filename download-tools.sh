#!/bin/sh
#
# Downloads every tool needed to build Drunken Maksim Sim into ./tools:
#
#   tools/jdk          Eclipse Temurin JDK 17 (Gradle and AGP run on it)
#   tools/android-sdk  Android SDK: cmdline-tools, platform-tools,
#                      platform android-35, build-tools 35.0.0
#
# It also accepts the SDK licences, writes local.properties and warms the
# Gradle wrapper, so a fresh clone can go straight to ./build.sh with
# nothing installed system-wide.
#
# Usage:  ./download-tools.sh [--force]
#
#   --force   re-download components that are already in ./tools
#
# Needs: curl or wget, tar, and (on Windows/Git Bash) PowerShell or unzip.
#
set -eu

JDK_MAJOR=17
CMDLINE_TOOLS_BUILD=13114758
SDK_PLATFORM="platforms;android-35"
SDK_BUILD_TOOLS="build-tools;35.0.0"

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TOOLS="$ROOT/tools"
JDK_DIR="$TOOLS/jdk"
SDK_DIR="$TOOLS/android-sdk"
DL="$TOOLS/downloads"

FORCE=0
if [ "${1:-}" = "--force" ]; then
    FORCE=1
elif [ $# -gt 0 ]; then
    echo "Usage: $0 [--force]" >&2
    exit 2
fi

say() { printf '\n== %s\n' "$*"; }

# ------------------------------------------------------------- host detection

case "$(uname -s)" in
    Linux*)               OS=linux;   CT_OS=linux; JDK_EXT=tar.gz ;;
    Darwin*)              OS=mac;     CT_OS=mac;   JDK_EXT=tar.gz ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows; CT_OS=win;   JDK_EXT=zip    ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
    x86_64|amd64)  ARCH=x64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "Unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

echo "Host: $OS/$ARCH   target: $TOOLS"

# ------------------------------------------------------------------- helpers

fetch() {
    # fetch <url> <outfile>
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --progress-bar -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "$2" "$1"
    else
        echo "Need curl or wget on PATH." >&2
        exit 1
    fi
}

unzip_to() {
    # unzip_to <zipfile> <destdir>
    mkdir -p "$2"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$1" -d "$2"
    elif command -v powershell >/dev/null 2>&1; then
        powershell -NoProfile -Command \
            "Expand-Archive -Force -LiteralPath '$(winpath "$1")' -DestinationPath '$(winpath "$2")'"
    else
        echo "Need unzip (or PowerShell) to unpack $1." >&2
        exit 1
    fi
}

winpath() {
    # Native path for tools that are not POSIX-aware (Windows only).
    if [ "$OS" = windows ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

propspath() {
    # Path for local.properties: a Windows drive path with forward slashes,
    # so it needs no escaping and the Windows JVM can still resolve it.
    if [ "$OS" = windows ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

java_bin() {
    if [ -x "$JDK_DIR/bin/java" ]; then
        echo "$JDK_DIR/bin/java"
    elif [ -x "$JDK_DIR/bin/java.exe" ]; then
        echo "$JDK_DIR/bin/java.exe"
    fi
}

mkdir -p "$TOOLS" "$DL"

# ----------------------------------------------------------------------- JDK

if [ "$FORCE" = 1 ]; then
    rm -rf "$JDK_DIR"
fi

if [ -n "$(java_bin)" ]; then
    say "JDK already present: $JDK_DIR"
else
    say "Downloading Eclipse Temurin JDK $JDK_MAJOR ($OS/$ARCH)"
    JDK_URL="https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
    JDK_ARCHIVE="$DL/jdk-$JDK_MAJOR.$JDK_EXT"
    fetch "$JDK_URL" "$JDK_ARCHIVE"

    say "Unpacking JDK"
    STAGE="$DL/jdk-stage"
    rm -rf "$STAGE"
    mkdir -p "$STAGE"
    if [ "$JDK_EXT" = "zip" ]; then
        unzip_to "$JDK_ARCHIVE" "$STAGE"
    else
        tar -xzf "$JDK_ARCHIVE" -C "$STAGE"
    fi

    # The archive holds a single versioned top directory; on macOS the real
    # JDK sits under <top>/Contents/Home.
    TOP=$(find "$STAGE" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    if [ -d "$TOP/Contents/Home" ]; then
        TOP="$TOP/Contents/Home"
    fi
    mv "$TOP" "$JDK_DIR"
    rm -rf "$STAGE" "$JDK_ARCHIVE"
fi

JAVA=$(java_bin)
if [ -z "$JAVA" ]; then
    echo "JDK unpack failed: no java under $JDK_DIR/bin." >&2
    exit 1
fi
"$JAVA" -version

export JAVA_HOME="$(winpath "$JDK_DIR")"

# -------------------------------------------------------------- Android SDK

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
[ "$OS" = windows ] && SDKMANAGER="$SDKMANAGER.bat"

if [ "$FORCE" = 1 ]; then
    rm -rf "$SDK_DIR/cmdline-tools"
fi

if [ -f "$SDKMANAGER" ]; then
    say "Android command-line tools already present"
else
    say "Downloading Android command-line tools ($CT_OS, build $CMDLINE_TOOLS_BUILD)"
    CT_ZIP="$DL/cmdline-tools.zip"
    fetch "https://dl.google.com/android/repository/commandlinetools-$CT_OS-${CMDLINE_TOOLS_BUILD}_latest.zip" "$CT_ZIP"

    say "Unpacking command-line tools"
    STAGE="$DL/ct-stage"
    rm -rf "$STAGE"
    unzip_to "$CT_ZIP" "$STAGE"
    mkdir -p "$SDK_DIR/cmdline-tools"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$STAGE/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$STAGE" "$CT_ZIP"
fi

say "Accepting Android SDK licences"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$(winpath "$SDK_DIR")" --licenses >/dev/null || true

say "Installing SDK packages"
"$SDKMANAGER" --sdk_root="$(winpath "$SDK_DIR")" \
    "platform-tools" "$SDK_PLATFORM" "$SDK_BUILD_TOOLS"

# ---------------------------------------------------------- local.properties

say "Writing local.properties"
printf 'sdk.dir=%s\n' "$(propspath "$SDK_DIR")" > "$ROOT/local.properties"
cat "$ROOT/local.properties"

# ----------------------------------------------------------- Gradle wrapper

say "Downloading the Gradle distribution (wrapper warm-up)"
cd "$ROOT"
chmod +x ./gradlew 2>/dev/null || true
./gradlew --version

say "Done. Build with:  ./build.sh"
