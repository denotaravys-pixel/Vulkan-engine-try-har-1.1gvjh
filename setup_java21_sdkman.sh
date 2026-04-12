#!/bin/bash
# Initialize SDKMAN
export SDKMAN_DIR="$HOME/.sdkman"
[[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"

# Use Java 21
sdk use java 21.0.2-open

# Set environment variables
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
export PATH="$JAVA_HOME/bin:$PATH"

echo "Java version:"
java -version
echo "JAVA_HOME: $JAVA_HOME"