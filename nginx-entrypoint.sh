#!/bin/sh
set -eu

htpasswd_file="/etc/nginx/.htpasswd"

if [ "${BASIC_AUTH_HTPASSWD:-}" != "" ]; then
  printf "%s\n" "$BASIC_AUTH_HTPASSWD" > "$htpasswd_file"
else
  # Ensure the file exists so nginx auth_basic_user_file does not error.
  : > "$htpasswd_file"
fi

exec "$@"
