#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ ! -x ./gradlew ]]; then
  echo 'Use Gradle 8.13 instalado ou gere o wrapper conforme README.'
  exit 1
fi
./gradlew verifyPlayRelease :app:testReleaseUnitTest :app:lintRelease :app:bundleRelease --no-daemon
printf '%s\n' 'AAB assinado: app/build/outputs/bundle/release/app-release.aab' 'A publicação e os testes na Play Store são etapas separadas.'
