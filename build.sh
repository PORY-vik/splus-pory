#!/bin/sh
# Builds dist/splusjava.jar (Java 7 compatible class files) and optionally runs the tests.
#
#   ./build.sh          compile + jar
#   ./build.sh test     compile + jar + run the unit tests
#
# Requirements: a JDK (javac). Set JAVAC to override the compiler command, e.g.
#   JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main" ./build.sh
set -e
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
OUT=build/classes
rm -rf build
mkdir -p "$OUT" dist

# Source level 8 is the lowest that modern JDKs (12+) still accept. The code base only uses
# Java 7 language features and APIs (checked by tools/check_java7.py), and the class files are
# patched to version 51 (Java 7) below so old Android build tools (dx) accept them.
find src/main/java -name '*.java' > build/sources.txt
$JAVAC -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT" @build/sources.txt

python3 tools/make_jar.py "$OUT" dist/splusjava.jar
echo "Built dist/splusjava.jar"

if [ "$1" = "test" ]; then
    mkdir -p build/test-classes
    find src/test/java -name '*.java' > build/test-sources.txt
    $JAVAC -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$OUT" -d build/test-classes @build/test-sources.txt
    JAVA="${JAVA:-java}"
    $JAVA -cp "$OUT:build/test-classes" splusjava.AllTests
fi
