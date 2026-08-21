#!/bin/sh
set -eu

mkdir -p /data/uploads /app/logs
chown -R sse:sse /data/uploads /app/logs

exec gosu sse "$@"
