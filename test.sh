#!/bin/sh
# Compile and run the tests with nothing but a JDK (17+).
set -e
cd "$(dirname "$0")"
rm -rf build
javac -Xlint:all,-serial,-try -Werror --release 17 -d build/main $(find src/main -name '*.java')
javac --release 17 -cp build/main -d build/test $(find src/test -name '*.java')
java -cp build/main:build/test com.kosa8.ClientTest
