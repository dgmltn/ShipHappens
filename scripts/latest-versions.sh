#!/usr/bin/env bash
set -euo pipefail
mc() { curl -sf "https://repo.maven.apache.org/maven2/$1/maven-metadata.xml" | sed -n 's/.*<release>\(.*\)<\/release>.*/\1/p'; }
gm() { curl -sf "https://dl.google.com/android/maven2/$1/group-index.xml"; }
echo "kotlin                 $(mc org/jetbrains/kotlin/kotlin-stdlib)"
echo "compose-multiplatform  $(mc org/jetbrains/compose/compose-gradle-plugin)"
echo "ktor                   $(mc io/ktor/ktor-client-core)"
echo "koin-bom               $(mc io/insert-koin/koin-bom)"
echo "coroutines             $(mc org/jetbrains/kotlinx/kotlinx-coroutines-core)"
echo "serialization          $(mc org/jetbrains/kotlinx/kotlinx-serialization-json)"
echo "datetime               $(mc org/jetbrains/kotlinx/kotlinx-datetime)"
echo "ksp                    $(mc com/google/devtools/ksp/symbol-processing-api)"
echo "lifecycle-jb           $(mc org/jetbrains/androidx/lifecycle/lifecycle-viewmodel)"
echo "navigation3-jb         $(mc org/jetbrains/androidx/navigation3/navigation3-runtime || echo 'NOT FOUND - STOP AND REPORT')"
echo "--- google maven: pick highest STABLE (no -alpha/-beta/-rc) from each list ---"
echo "room3:";     gm androidx/room3
echo; echo "sqlite:";    gm androidx/sqlite | grep -o 'sqlite-bundled versions="[^"]*"'
echo; echo "datastore:"; gm androidx/datastore | grep -o 'datastore-preferences-core versions="[^"]*"'
echo; echo "agp:";       gm com/android/tools/build | grep -o '<gradle versions="[^"]*"'
