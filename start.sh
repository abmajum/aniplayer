#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

COURSES_DIR="${COURSES_DIR:-$REPO_ROOT/courses}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"

mkdir -p "$COURSES_DIR" "$DATA_DIR"

export COURSES_PATH="$COURSES_DIR"
export DATA_PATH="$DATA_DIR"

cd "$REPO_ROOT"
docker compose up --build -d
