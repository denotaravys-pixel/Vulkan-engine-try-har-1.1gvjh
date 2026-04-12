#!/bin/bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
echo "Java version:"
java -version
echo "JAVA_HOME: $JAVA_HOME"