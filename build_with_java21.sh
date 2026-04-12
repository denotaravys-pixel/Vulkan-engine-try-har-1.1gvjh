#!/bin/bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java from: $JAVA_HOME"
java -version

echo "Building project..."
cd /workspaces/Vulkan-engine-try-har-1.1
./gradlew build