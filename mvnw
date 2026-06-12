#!/bin/sh
# Maven Wrapper script
set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$MAVEN_WRAPPER_JAR" ]; then
    echo "Downloading Maven wrapper jar..."
    WRAPPER_URL=$(grep 'wrapperUrl' "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2- | tr -d '[:space:]')
    mkdir -p "$(dirname "$MAVEN_WRAPPER_JAR")"
    if command -v wget > /dev/null; then
        wget -q -O "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
    elif command -v curl > /dev/null; then
        curl -sL -o "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
    else
        echo "Error: Neither wget nor curl found. Cannot download Maven wrapper."
        exit 1
    fi
fi

exec java \
    $MAVEN_OPTS \
    -classpath "$MAVEN_WRAPPER_JAR" \
    "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
    org.apache.maven.wrapper.MavenWrapperMain \
    "$@"
