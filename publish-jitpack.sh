#!/usr/bin/env bash
set -euo pipefail

GROUP="com.github.TempTalkOrg"
MODULES=(
  "livekit-android"
  "livekit-android-camerax"
  "ttsignal"
  "livekit-android-track-processors"
)
GRADLE_MODULES=(
  ":livekit-android-sdk"
  ":livekit-android-camerax"
  ":ttsignal"
  ":livekit-android-track-processors"
)
JITPACK_URL="https://jitpack.io"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

print_usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Publish libraries to JitPack by creating a Git tag and triggering builds.

OPTIONS:
  -v, --version VERSION   Version to publish (e.g. 2.23.2.5). Defaults to VERSION_NAME in gradle.properties.
  -t, --tag-only          Only create and push the Git tag, don't trigger JitPack builds.
  -b, --build-only        Only trigger JitPack builds for an existing tag, don't create a new tag.
  -s, --status            Check the build status for a given version on JitPack.
  -d, --dry-run           Show what would be done without executing.
  -h, --help              Show this help message.

EXAMPLES:
  $0                          # Use version from gradle.properties, tag + trigger
  $0 -v 2.23.2.6              # Publish version 2.23.2.6
  $0 -s -v 2.23.2.5           # Check build status for 2.23.2.5
  $0 -b -v 2.23.2.5           # Re-trigger JitPack build for existing tag
  $0 -t -v 2.23.2.5           # Only create and push Git tag

ARTIFACTS:
  ${GROUP}:livekit-android
  ${GROUP}:livekit-android-camerax
  ${GROUP}:ttsignal
  ${GROUP}:livekit-android-track-processors

EOF
}

get_version_from_gradle() {
  local props_file="$1"
  if [[ ! -f "$props_file" ]]; then
    echo ""
    return
  fi
  grep '^VERSION_NAME=' "$props_file" 2>/dev/null | cut -d'=' -f2 | tr -d '[:space:]' || true
}

VERSION=""
TAG_ONLY=false
BUILD_ONLY=false
CHECK_STATUS=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--version)   VERSION="$2"; shift 2 ;;
    -t|--tag-only)  TAG_ONLY=true; shift ;;
    -b|--build-only) BUILD_ONLY=true; shift ;;
    -s|--status)    CHECK_STATUS=true; shift ;;
    -d|--dry-run)   DRY_RUN=true; shift ;;
    -h|--help)      print_usage; exit 0 ;;
    *)              echo -e "${RED}Unknown option: $1${NC}"; print_usage; exit 1 ;;
  esac
done

cd "$(dirname "$0")"

if [[ -z "$VERSION" ]]; then
  VERSION=$(get_version_from_gradle "gradle.properties")
  if [[ -z "$VERSION" ]]; then
    echo -e "${RED}Error: Could not determine version. Use -v to specify.${NC}"
    exit 1
  fi
fi

TAG="v${VERSION}"
REPO_OWNER="difftim"
REPO_NAME="client-sdk-android"

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  JitPack Publish Script${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""
echo -e "  Group:    ${GREEN}${GROUP}${NC}"
echo -e "  Version:  ${GREEN}${VERSION}${NC}"
echo -e "  Tag:      ${GREEN}${TAG}${NC}"
echo -e "  Repo:     ${GREEN}${REPO_OWNER}/${REPO_NAME}${NC}"
echo ""
echo -e "  Artifacts:"
for m in "${MODULES[@]}"; do
  echo -e "    - ${YELLOW}${GROUP}:${m}:${VERSION}${NC}"
done
echo ""

# ── Pre-flight checks ──
echo -e "${CYAN}[0/3] Pre-flight checks...${NC}"

# Check ttsignal version alignment
ttsignal_version=$(get_version_from_gradle "ttsignal/gradle.properties")
if [[ -n "$ttsignal_version" && "$ttsignal_version" != "$VERSION" ]]; then
  echo -e "  ${RED}ERROR: ttsignal has VERSION_NAME=${ttsignal_version}, expected ${VERSION}${NC}"
  echo -e "  ${RED}Remove VERSION_NAME from ttsignal/gradle.properties to inherit the root version.${NC}"
  exit 1
fi

# Check submodules are initialized
submodule_ok=true
if [[ ! -f "ttsignal/ttsignal/android/scripts/build-so.sh" ]]; then
  echo -e "  ${YELLOW}ttsignal submodule not initialized.${NC}"
  submodule_ok=false
fi
if [[ ! -f "protocol/protobufs/livekit_rtc.proto" ]]; then
  echo -e "  ${YELLOW}protocol submodule not initialized.${NC}"
  submodule_ok=false
fi
if ! $submodule_ok; then
  echo -e "  ${YELLOW}Initializing submodules...${NC}"
  if ! $DRY_RUN; then
    git submodule update --init --recursive
  fi
fi

echo -e "  ${GREEN}✓ All checks passed${NC}"
echo ""

# ── Check Status ──
if $CHECK_STATUS; then
  echo -e "${CYAN}Checking JitPack build status...${NC}"
  echo ""
  for m in "${MODULES[@]}"; do
    url="${JITPACK_URL}/api/builds/com.github.${REPO_OWNER}.${REPO_NAME}/${m}/${TAG}"
    echo -n "  ${m}: "
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "error")
    if [[ "$status" == "200" ]]; then
      response=$(curl -s "$url" 2>/dev/null)
      build_status=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','unknown'))" 2>/dev/null || echo "unknown")
      case "$build_status" in
        ok)      echo -e "${GREEN}✓ Build successful${NC}" ;;
        Error)   echo -e "${RED}✗ Build failed${NC}" ;;
        *)       echo -e "${YELLOW}⟳ Status: ${build_status}${NC}" ;;
      esac
    elif [[ "$status" == "404" ]]; then
      echo -e "${YELLOW}— Not found (not yet built)${NC}"
    else
      echo -e "${RED}? HTTP ${status}${NC}"
    fi
  done
  echo ""

  echo -e "${CYAN}Build logs:${NC}"
  echo -e "  ${JITPACK_URL}/#${REPO_OWNER}/${REPO_NAME}/${TAG}"
  echo ""

  echo -e "${CYAN}Dependency usage in build.gradle:${NC}"
  for m in "${MODULES[@]}"; do
    echo -e "  implementation '${GROUP}:${m}:${VERSION}'"
  done
  exit 0
fi

# ── Verify local build ──
echo -e "${CYAN}[1/3] Verifying local build...${NC}"
if $DRY_RUN; then
  echo -e "  ${YELLOW}(dry-run) Would run: ./gradlew assembleRelease for all 4 modules${NC}"
else
  echo -e "  Running assembleRelease for all modules..."
  assemble_tasks=()
  for gm in "${GRADLE_MODULES[@]}"; do
    assemble_tasks+=("${gm}:assembleRelease")
  done
  JITPACK=true ./gradlew "${assemble_tasks[@]}" --no-daemon -q
  echo -e "  ${GREEN}✓ Build passed${NC}"
fi
echo ""

# ── Create and push Git tag ──
if ! $BUILD_ONLY; then
  echo -e "${CYAN}[2/3] Creating Git tag '${TAG}'...${NC}"

  if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo -e "  ${YELLOW}Tag '${TAG}' already exists locally.${NC}"
    read -rp "  Overwrite? (y/N): " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
      if $DRY_RUN; then
        echo -e "  ${YELLOW}(dry-run) Would delete and recreate tag '${TAG}'${NC}"
      else
        git tag -d "$TAG"
        git push origin ":refs/tags/${TAG}" 2>/dev/null || true
        git tag -a "$TAG" -m "Release ${VERSION}"
        echo -e "  ${GREEN}✓ Tag recreated${NC}"
      fi
    else
      echo -e "  ${YELLOW}Skipping tag creation.${NC}"
    fi
  else
    if $DRY_RUN; then
      echo -e "  ${YELLOW}(dry-run) Would create tag '${TAG}'${NC}"
    else
      git tag -a "$TAG" -m "Release ${VERSION}"
      echo -e "  ${GREEN}✓ Tag created${NC}"
    fi
  fi

  if $DRY_RUN; then
    echo -e "  ${YELLOW}(dry-run) Would push tag '${TAG}' to origin${NC}"
  else
    git push origin "$TAG"
    echo -e "  ${GREEN}✓ Tag pushed to origin${NC}"
  fi
else
  echo -e "${CYAN}[2/3] Skipping tag creation (--build-only)${NC}"
fi
echo ""

# ── Trigger JitPack builds ──
if ! $TAG_ONLY; then
  echo -e "${CYAN}[3/3] Triggering JitPack builds...${NC}"
  echo ""

  for m in "${MODULES[@]}"; do
    lookup_url="${JITPACK_URL}/com/github/${REPO_OWNER}/${REPO_NAME}/${m}/${TAG}/${m}-${TAG}.pom"
    echo -n "  Triggering ${m}... "

    if $DRY_RUN; then
      echo -e "${YELLOW}(dry-run) Would request: ${lookup_url}${NC}"
    else
      http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 30 "$lookup_url" 2>/dev/null || echo "error")
      case "$http_code" in
        200) echo -e "${GREEN}✓ Already built${NC}" ;;
        *)   echo -e "${YELLOW}⟳ Build queued (HTTP ${http_code})${NC}" ;;
      esac
    fi
  done

  echo ""
  echo -e "${CYAN}========================================${NC}"
  echo -e "${GREEN}  Done!${NC}"
  echo -e "${CYAN}========================================${NC}"
  echo ""
  echo -e "  ${CYAN}Monitor builds at:${NC}"
  echo -e "  ${JITPACK_URL}/#${REPO_OWNER}/${REPO_NAME}/${TAG}"
  echo ""
  echo -e "  ${CYAN}Add to your project's build.gradle:${NC}"
  echo ""
  echo -e "  ${YELLOW}repositories {${NC}"
  echo -e "  ${YELLOW}    maven { url 'https://jitpack.io' }${NC}"
  echo -e "  ${YELLOW}}${NC}"
  echo ""
  echo -e "  ${YELLOW}dependencies {${NC}"
  for m in "${MODULES[@]}"; do
    echo -e "  ${YELLOW}    implementation '${GROUP}:${m}:${VERSION}'${NC}"
  done
  echo -e "  ${YELLOW}}${NC}"
else
  echo -e "${CYAN}[3/3] Skipping JitPack trigger (--tag-only)${NC}"
  echo ""
  echo -e "${CYAN}========================================${NC}"
  echo -e "${GREEN}  Tag pushed. Run with --build-only to trigger JitPack.${NC}"
  echo -e "${CYAN}========================================${NC}"
fi
echo ""
