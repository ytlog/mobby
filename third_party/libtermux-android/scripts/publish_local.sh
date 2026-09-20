#!/usr/bin/env bash
# Publish AAR to local Maven (~/.m2)
set -euo pipefail
echo "Publishing to local Maven repository..."
./gradlew :core:publishToMavenLocal
echo ""
echo "Add to your project:"
echo "  repositories { mavenLocal() }"
echo "  implementation 'com.github.libtermux:libtermux-android:1.0.0'"
