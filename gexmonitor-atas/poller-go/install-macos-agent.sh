#!/usr/bin/env bash
#
# Install GexPoller as a per-user macOS LaunchAgent.
#
# This is the macOS equivalent of the Windows workflow in the original install
# notes: "put a shortcut to GexPoller.exe into shell:startup". Instead of a
# shortcut it registers a launchd agent, so the poller starts at login, runs in
# the background and is restarted automatically if it ever dies.
#
# Usage:
#   ./install-macos-agent.sh            # install and start
#   ./install-macos-agent.sh status     # show state + tail the log
#   ./install-macos-agent.sh restart
#   ./install-macos-agent.sh uninstall
#
# Run ./build.sh first so that dist/GexPoller exists.

set -euo pipefail
cd "$(dirname "$0")"

LABEL="com.gexmonitor.pollbridge"
BIN_DIR="$HOME/.local/bin"
BIN="$BIN_DIR/GexPoller"
PLIST_DIR="$HOME/Library/LaunchAgents"
PLIST="$PLIST_DIR/$LABEL.plist"
LOG="$HOME/Library/Logs/gexpoller.log"
SRC="${SRC:-dist/GexPoller}"
DOMAIN="gui/$(id -u)"

case "${1:-install}" in
install)
	if [ ! -x "$SRC" ]; then
		echo "error: $SRC not found or not executable — run ./build.sh first" >&2
		exit 1
	fi

	mkdir -p "$BIN_DIR" "$PLIST_DIR" "$(dirname "$LOG")"
	install -m 0755 "$SRC" "$BIN"

	cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>$LABEL</string>
	<key>ProgramArguments</key>
	<array>
		<string>$BIN</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>ThrottleInterval</key>
	<integer>30</integer>
	<key>StandardOutPath</key>
	<string>$LOG</string>
	<key>StandardErrorPath</key>
	<string>$LOG</string>
</dict>
</plist>
EOF

	launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
	launchctl bootstrap "$DOMAIN" "$PLIST"

	echo "installed binary : $BIN"
	echo "launch agent     : $PLIST"
	echo "log              : $LOG"
	echo
	echo "It is running now and will start again at every login."
	echo "Remove it with:  $0 uninstall"
	;;

status)
	launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E "state|pid|last exit" || \
		echo "not loaded"
	echo
	echo "--- last log lines ($LOG) ---"
	tail -n 10 "$LOG" 2>/dev/null || echo "(no log yet)"
	;;

restart)
	launchctl kickstart -k "$DOMAIN/$LABEL"
	echo "restarted"
	;;

uninstall)
	launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
	rm -f "$PLIST"
	echo "unloaded and removed $PLIST"
	echo "binary left in place: $BIN (delete it manually if you want)"
	echo "data files left in place: ~/Library/Application Support/ATAS X/GexData"
	;;

*)
	echo "usage: $0 [install|status|restart|uninstall]" >&2
	exit 2
	;;
esac
