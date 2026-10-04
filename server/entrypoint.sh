#!/bin/sh
# Volumes mount root-owned: give the storage dir to the unprivileged `node` user, then run as it.
set -e
mkdir -p "$STORAGE_DIR"
chown -R node:node "$STORAGE_DIR"
exec setpriv --reuid=node --regid=node --init-groups "$@"
