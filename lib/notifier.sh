#!/usr/bin/env bash

set -euo pipefail

NOTIFIER_JAR="${NOTIFIER_DIR}/home-lab-notifier.jar"

_validate(){
  if [[ ! -f "$NOTIFIER_JAR" ]]; then
      echo "Notifier JAR not found: $NOTIFIER_JAR" >&2
      exit 1
  fi
}

notifier_start() {
  _validate
  java -jar "$NOTIFIER_JAR" \
      --event START
}

notifier_success() {
  _validate
  local MESSAGE="$1"
  local SNAPSHOT="$2"
  local DURATION="$3"

  java -jar "$NOTIFIER_JAR" \
      --event SUCCESS \
      --message "$MESSAGE" \
      --snapshot "$SNAPSHOT" \
      --duration "$DURATION"
}

notifier_failure() {
  _validate
  local MESSAGE="$1"
  local SNAPSHOT="$2"
  local DURATION="$3"
  args=(
      --event FAILURE
      --message "$MESSAGE"
  )

  [[ -n "$SNAPSHOT" ]] && args+=(--snapshot "$SNAPSHOT")
  [[ -n "$DURATION" ]] && args+=(--duration "$DURATION")

  java -jar "$NOTIFIER_JAR" "${args[@]}"
}