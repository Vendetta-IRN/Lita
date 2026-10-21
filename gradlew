#!/bin/sh
# Minimal wrapper launcher. gradle-wrapper.jar could NOT be bundled (the generation
# environment had no network access). See README.md ("Gradle wrapper") to create it once.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar is missing." >&2
  echo "Open the project in Android Studio (it syncs using gradle-wrapper.properties)," >&2
  echo "or run once with a local Gradle: gradle wrapper --gradle-version 8.13" >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
