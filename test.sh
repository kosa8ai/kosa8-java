#!/bin/sh
# Compile and run the tests with nothing but a JDK (17+).
set -e
cd "$(dirname "$0")"
rm -rf build
javac -Xlint:all,-serial,-try -Werror --release 17 -d build/main $(find src/main -name '*.java')
javac --release 17 -cp build/main -d build/test $(find src/test -name '*.java')
# The classpath separator is ';' for a Windows JVM (Git Bash, MSYS, Cygwin), ':' elsewhere.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=';' ;;
  *) sep=':' ;;
esac
java -cp "build/main${sep}build/test" com.kosa8.ClientTest
