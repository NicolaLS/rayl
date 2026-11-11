#!/bin/sh
export PATH="/opt/homebrew/opt/libpq/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
# Prints the feedback testers sent from the app, newest first.
#
#   tools/feedback/feedback.sh              # the latest 50
#   tools/feedback/feedback.sh 200          # the latest 200
#   tools/feedback/feedback.sh 50 widget    # only one kind: widget, idea, problem, other
#
# Needs `psql`, the Railway CLI logged in (`railway login`), and an SSH key
# registered with the Railway account (`railway connect --ssh` uses it).
# The script tunnels to the private Postgres service via `railway connect`.
set -eu

limit="${1:-50}"
kind="${2:-}"
case "$limit" in ''|*[!0-9]*) echo "usage: feedback.sh [count] [kind]" >&2; exit 2;; esac
case "$kind" in ''|widget|idea|problem|other) ;; *) echo "kind must be widget, idea, problem or other" >&2; exit 2;; esac

# Open an SSH tunnel to the private Postgres service.
# railway connect blocks until Ctrl+C, so run it in the background and parse
# its output for the local connection URL.
tunnel_out="$(mktemp)"
cleanup() {
  kill "${tunnel_pid:-}" 2>/dev/null || true
  rm -f "$tunnel_out"
}
trap cleanup EXIT INT TERM

railway connect Postgres --tunnel-only --ssh --environment production > "$tunnel_out" 2>&1 &
tunnel_pid=$!

# Wait for the tunnel to print the connection URL (up to 15 s).
url=""
i=0
while [ "$i" -lt 15 ]; do
  url="$(sed -n 's/^.*URL:[[:space:]]*\(.*\)/\1/p' "$tunnel_out" | head -1)"
  [ -n "$url" ] && break
  sleep 1
  i=$((i + 1))
done

if [ -z "$url" ]; then
  echo "Failed to open database tunnel." >&2
  cat "$tunnel_out" >&2
  exit 1
fi

where=""
[ -z "$kind" ] || where="where kind = '$kind'"
psql "$url" --no-psqlrc --pset=pager=off -x -c "
  select to_char(received_at, 'YYYY-MM-DD HH24:MI') as at, kind, platform, contact, message
  from feedback $where order by id desc limit $limit"
