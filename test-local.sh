#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_OUT="$PROJECT_DIR/.build/unit-tests"
mkdir -p "$TEST_OUT"
find "$TEST_OUT" -mindepth 1 -delete

java -m jdk.compiler/com.sun.tools.javac.Main \
  -source 8 -target 8 \
  -encoding UTF-8 \
  -d "$TEST_OUT" \
  "$PROJECT_DIR/app/src/main/java/de/gabriel/ankunftsalarm/DistanceCalculator.java" \
  "$PROJECT_DIR/app/src/main/java/de/gabriel/ankunftsalarm/ArrivalConfirmation.java" \
  "$PROJECT_DIR/tests/DistanceCalculatorTest.java" \
  "$PROJECT_DIR/tests/ArrivalConfirmationTest.java"

java -cp "$TEST_OUT" de.gabriel.ankunftsalarm.DistanceCalculatorTest
java -cp "$TEST_OUT" de.gabriel.ankunftsalarm.ArrivalConfirmationTest
