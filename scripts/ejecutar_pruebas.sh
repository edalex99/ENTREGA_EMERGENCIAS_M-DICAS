#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/main out/test
find src/main/java -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -d out/main
find src/test/java -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -cp out/main -d out/test
java -ea -cp out/main:out/test edu.iudigital.emergencias.ProjectTests
