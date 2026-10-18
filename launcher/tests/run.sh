#!/usr/bin/env bash
# Regression checks for catalog reuse, identity, layout, recent opens and navigation without Android dependencies.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
test_classes="$(mktemp -d "${TMPDIR:-/tmp}/tvhome-tests.XXXXXX")"
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" \
  app/src/main/java/com/gathin/tvhome/AppCatalogCache.java \
  app/src/main/java/com/gathin/tvhome/StableItemIds.java \
  app/src/main/java/com/gathin/tvhome/HomeLayoutState.java \
  app/src/main/java/com/gathin/tvhome/RecentAppsState.java \
  app/src/main/java/com/gathin/tvhome/SectionNavigation.java \
  tests/com/gathin/tvhome/AppCatalogCacheTest.java \
  tests/com/gathin/tvhome/StableItemIdsTest.java \
  tests/com/gathin/tvhome/HomeLayoutStateTest.java \
  tests/com/gathin/tvhome/RecentAppsStateTest.java \
  tests/com/gathin/tvhome/SectionNavigationTest.java
java -cp "$test_classes" com.gathin.tvhome.AppCatalogCacheTest
java -cp "$test_classes" com.gathin.tvhome.StableItemIdsTest
java -cp "$test_classes" com.gathin.tvhome.HomeLayoutStateTest
java -cp "$test_classes" com.gathin.tvhome.RecentAppsStateTest
java -cp "$test_classes" com.gathin.tvhome.SectionNavigationTest
