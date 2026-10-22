#!/bin/sh
# Lance Photo Extractor avec Java 21 (JavaFX et JavaCV sont testés avec cette version).
JAVA_HOME="$(/usr/libexec/java_home -v 21)" || { echo "Java 21 est requis." >&2; exit 1; }
export JAVA_HOME
cd "$(dirname "$0")" && exec mvn -q javafx:run
