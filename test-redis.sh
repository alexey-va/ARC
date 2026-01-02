#!/bin/bash
# Helper script to run Redis integration tests with Colima
# Usage: ./test-redis.sh

# Ensure Colima is running
if ! docker ps > /dev/null 2>&1; then
    echo "Error: Docker/Colima is not running. Please start Colima first:"
    echo "  colima start"
    exit 1
fi

# Use Colima context
docker context use colima 2>/dev/null || true

# Find Colima socket (try both locations)
# Colima typically uses .colima/docker.sock (not default/docker.sock)
# Use -e to check if file exists (works for sockets too)
if [ -e "$HOME/.colima/docker.sock" ]; then
    COLIMA_SOCKET="$HOME/.colima/docker.sock"
elif [ -e "$HOME/.colima/default/docker.sock" ]; then
    COLIMA_SOCKET="$HOME/.colima/default/docker.sock"
else
    echo "Error: Could not find Colima socket"
    echo "  Expected: $HOME/.colima/docker.sock or $HOME/.colima/default/docker.sock"
    exit 1
fi

# Pre-pull Redis image to avoid timeout issues
echo "Pulling Redis image..."
docker pull redis:7-alpine

# Configure Testcontainers for Colima
export DOCKER_HOST="unix://$COLIMA_SOCKET"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$COLIMA_SOCKET"
export TESTCONTAINERS_RYUK_DISABLED=true
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

echo "Running Redis integration tests with Colima..."
echo "  DOCKER_HOST=$DOCKER_HOST"
echo "  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=$TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"
echo "  TESTCONTAINERS_RYUK_DISABLED=$TESTCONTAINERS_RYUK_DISABLED"
echo ""

./gradlew test --tests 'ru.arc.network.RedisManagerIntegrationTest' --no-daemon
