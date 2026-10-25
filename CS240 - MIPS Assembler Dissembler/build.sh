#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all \
    -d build/classes src/Mhc.java src/MhcTest.java
java -cp build/classes MhcTest
java -m jdk.jartool/sun.tools.jar.Main --create --file mhc.jar --main-class Mhc \
    -C build/classes .
echo "Built mhc.jar"
