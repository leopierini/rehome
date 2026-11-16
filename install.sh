#!/bin/sh
# Install rehome on a Philips Android TV: finds the TV, backs it up, disables the cleanup list (never
# uninstalls), installs the launcher and its Wi-Fi companion, and makes rehome Home. It asks before changing
# anything. Options: python3 tools/rehome.py install --help. Undo: python3 tools/rehome.py restore.
exec python3 "$(dirname "$0")/tools/rehome.py" install "$@"
