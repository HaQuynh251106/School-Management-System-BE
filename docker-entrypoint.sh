#!/bin/sh
set -eu

# Named volumes are mounted after the image is built and can therefore be
# owned by root even though the application runs as the unprivileged SSE user.
mkdir -p /data/uploads /app/logs
chown -R sse:sse /data/uploads /app/logs

exec setpriv --reuid=sse --regid=sse --init-groups "$@"
