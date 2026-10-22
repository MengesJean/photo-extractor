#!/bin/sh
# Lance Photo Extractor avec Java 25 (JavaFX et JavaCV sont testés avec cette version).
JAVA_HOME="$(/usr/libexec/java_home -v 25)" || { echo "Java 25 est requis." >&2; exit 1; }
export JAVA_HOME
cd "$(dirname "$0")" && exec mvn -q javafx:run
