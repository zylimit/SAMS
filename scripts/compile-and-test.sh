#!/usr/bin/env bash
# Compile SAMS backend with a curl-populated ~/.m2 classpath (Maven HTTP hangs here).
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BE="$ROOT/backend"
CP_FILE="$BE/.classpath-jars"
if [ ! -s "$CP_FILE" ]; then
  echo "missing $CP_FILE — run scripts/fetch-maven-deps.py first" >&2
  exit 1
fi
CP="$(paste -sd: "$CP_FILE")"
OUT="$BE/target/classes"
TEST_OUT="$BE/target/test-classes"
rm -rf "$OUT" "$TEST_OUT"
mkdir -p "$OUT" "$TEST_OUT"
# resources
if [ -d "$BE/src/main/resources" ]; then
  cp -a "$BE/src/main/resources/." "$OUT/"
fi
echo "== javac main =="
find "$BE/src/main/java" -name '*.java' > "$BE/target/main.sources"
javac --release 21 -parameters -encoding UTF-8 -cp "$CP" -d "$OUT" @"$BE/target/main.sources"
echo "== javac test =="
find "$BE/src/test/java" -name '*.java' > "$BE/target/test.sources"
javac --release 21 -parameters -encoding UTF-8 -cp "$CP:$OUT" -d "$TEST_OUT" @"$BE/target/test.sources"
JUNIT=$(grep 'junit-platform-console-standalone' "$CP_FILE" | head -1)
echo "== junit =="
java -jar "$JUNIT" execute --class-path "$CP:$OUT:$TEST_OUT" --select-package com.sams
echo "COMPILE+TEST OK"
