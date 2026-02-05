#!/bin/sh

#
# Gradle wrapper script.
# Delegates to the system-installed Gradle or downloads a distribution.
# For full wrapper support, run: gradle wrapper --gradle-version 8.8
#

APP_HOME=$( cd "${0%"${0##*/}"}." && pwd -P ) || exit

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$CLASSPATH" ] && command -v java >/dev/null 2>&1; then
    exec java \
        -Dorg.gradle.appname="${0##*/}" \
        -classpath "$CLASSPATH" \
        org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "ERROR: Gradle not found. Install Gradle or run: gradle wrapper" >&2
    exit 1
fi
