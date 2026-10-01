#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="/home/bsl-adminit/Projects/cartonloading"
BE_DIR="$PROJECT_ROOT/cartonloading_be"
UPLOAD_DIR="$PROJECT_ROOT/uploads"
IMAGE_NAME="cartonloading_be:latest"
CONTAINER_NAME="cartonloading_be"

mkdir -p "$UPLOAD_DIR"
cd "$BE_DIR"

docker build -t "$IMAGE_NAME" .
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  --network host \
  -v "$UPLOAD_DIR:/app/uploads" \
  -e SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:27017/cartonloading" \
  -e SERVER_ADDRESS="0.0.0.0" \
  -e SERVER_PORT="8083" \
  -e SERVER_SSL_ENABLED="false" \
  "$IMAGE_NAME"

echo "Carton Loading BE started on http://10.232.100.69:8083"
docker ps --filter "name=$CONTAINER_NAME"
