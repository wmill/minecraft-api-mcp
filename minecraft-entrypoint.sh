#!/bin/sh
set -eu

DIST_DIR="/opt/minecraft-dist"
LIVE_DIR="/minecraft"

mkdir -p "$LIVE_DIR" "$LIVE_DIR/mods" "$LIVE_DIR/logs" "$LIVE_DIR/world"

copy_file() {
    src="$1"
    dest="$2"
    install -m 0644 "$src" "$dest"
}

# Refresh image-managed binaries on every start so image updates reach the volume.
copy_file "$DIST_DIR/fabric-server-launch.jar" "$LIVE_DIR/fabric-server-launch.jar"

for mod in "$DIST_DIR"/mods/*.jar; do
    [ -e "$mod" ] || continue
    copy_file "$mod" "$LIVE_DIR/mods/$(basename "$mod")"
done

# Seed default config only if it does not already exist in the persistent volume.
for config in server.properties eula.txt; do
    if [ ! -e "$LIVE_DIR/$config" ]; then
        copy_file "$DIST_DIR/$config" "$LIVE_DIR/$config"
    fi
done

chown -R minecraft:minecraft "$LIVE_DIR"

cd "$LIVE_DIR"
exec gosu minecraft "$@"
