#!/bin/sh
# jawata installer bootstrap — https://jawata.org
# Fetches and runs the jawata-studio installer (Linux AppImage / macOS dmg),
# which installs the desktop manager; studio then downloads and manages the
# engine (jawata-mcp) and wires up Claude Code and Cursor.
set -e
exec bash -c "$(curl -fsSL https://raw.githubusercontent.com/haraldwegner/jawata-studio/main/install.sh)"
