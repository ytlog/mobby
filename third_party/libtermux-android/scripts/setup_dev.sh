#!/usr/bin/env bash
set -euo pipefail
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

info "LibTermux Android — Dev Setup"

# Check Java
if ! command -v java &>/dev/null; then error "Java not found. Install JDK 17+."; fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
[[ "$JAVA_VER" -lt 17 ]] && error "Java 17+ required. Found: $JAVA_VER"
info "Java $JAVA_VER ✓"

# Check ANDROID_HOME
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for dir in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk"; do
        [[ -d "$dir" ]] && export ANDROID_HOME="$dir" && break
    done
    [[ -z "${ANDROID_HOME:-}" ]] && error "ANDROID_HOME not found."
fi
info "ANDROID_HOME=$ANDROID_HOME ✓"

chmod +x gradlew
info "gradlew permissions set ✓"

info "Syncing Gradle dependencies..."
./gradlew dependencies -q && info "Done ✓"

echo -e "\n${GREEN}Setup complete!${NC}"
echo "Commands:"
echo "  ./gradlew :core:testDebugUnitTest"
echo "  ./gradlew :core:assembleRelease"
echo "  ./gradlew :sample:installDebug"
