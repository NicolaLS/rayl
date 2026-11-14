#!/bin/sh
export PATH="/opt/homebrew/opt/libpq/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
# Says whether the Rayl backend needs attention today. Run it by hand or from cron: it prints one
# line per check and exits 1 when any of them says ATTENTION.
#
#   tools/daily/daily.sh
#
# It looks at four things:
#   - how full the database volume is, and how much of the usage-data budget is used
#     (the backend stops storing usage data at 3 GB; see backend/rayl/README.md);
#   - whether usage data arrived much faster than usual, or under many app versions,
#     which is what junk looks like;
#   - how much feedback arrived (read it with tools/feedback/feedback.sh);
#   - whether production serves fresh prices.
#
# Needs `psql`, `curl`, the Railway CLI logged in (`railway login`), and an SSH key registered
# with the Railway account. It only reads.
set -eu

PROJECT=9bbf2625-4edc-4a92-bc7c-b3e9f6c840be
API=https://rayl-production.up.railway.app
VOLUME_MB=5000
BUDGET_MB=3072

attention=0
ok() { echo "ok         $1"; }
attn() { echo "ATTENTION  $1"; attention=1; }

# --- prices: the one thing payments depend on -------------------------------------------------
prices="$(curl -s -m 15 "$API/v1/bitcoin/prices" || true)"
as_of="$(printf '%s' "$prices" | sed -n 's/.*"asOf":\([0-9]*\).*/\1/p')"
if [ -z "$as_of" ]; then
  attn "prices: production does not answer with prices"
else
  age=$(( $(date +%s) - as_of ))
  if [ "$age" -gt 600 ]; then
    attn "prices: the newest quote is $age seconds old"
  else
    ok "prices: served, $age seconds old"
  fi
fi

# --- database ---------------------------------------------------------------------------------
tunnel_out="$(mktemp)"
# The CLI tunnels through an ssh process that outlives it, so that one is stopped first.
close_tunnel() {
  [ -n "${tunnel_pid:-}" ] || return 0
  pkill -P "$tunnel_pid" 2>/dev/null || true
  kill "$tunnel_pid" 2>/dev/null || true
  wait "$tunnel_pid" 2>/dev/null || true
  tunnel_pid=""
}
cleanup() {
  close_tunnel
  rm -f "$tunnel_out"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

# The CLI sometimes gives up waiting for its own tunnel; a second try opens it.
url=""
attempt=0
while [ -z "$url" ] && [ "$attempt" -lt 3 ]; do
  attempt=$((attempt + 1))
  railway connect Postgres -p "$PROJECT" --tunnel-only --ssh --environment production \
    > "$tunnel_out" 2>&1 &
  tunnel_pid=$!
  while kill -0 "$tunnel_pid" 2>/dev/null; do
    url="$(sed -n 's/^.*URL:[[:space:]]*\(.*\)/\1/p' "$tunnel_out" | head -1)"
    [ -n "$url" ] && break
    sleep 1
  done
  [ -n "$url" ] || close_tunnel
done
if [ -z "$url" ]; then
  attn "database: the tunnel did not open in $attempt tries, so nothing below was checked"
  cat "$tunnel_out" >&2
  exit 1
fi

# One row: database MB, usage-data MB, events in the last 24 hours, events a day over the seven
# days before, app versions seen in the last 24 hours, feedback in the last 24 hours.
row="$(psql "$url" --no-psqlrc -At -F ' ' -c "
  select
    pg_database_size(current_database()) / 1048576,
    (select coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint / 1048576 from pg_class c
       join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = current_schema() and c.relname in
        ('telemetry_events', 'telemetry_daily_counts', 'telemetry_daily_measures')),
    (select count(*) from telemetry_events where received_hour >= now() - interval '24 hours'),
    (select count(*) / 7 from telemetry_events
      where received_hour >= now() - interval '8 days'
        and received_hour < now() - interval '24 hours'),
    (select count(distinct app_version) from telemetry_events
      where received_hour >= now() - interval '24 hours'),
    (select count(*) from feedback where received_at >= now() - interval '24 hours')
")" || { attn "database: the query failed"; exit 1; }
# shellcheck disable=SC2086
set -- $row
db_mb=$1 usage_mb=$2 events=$3 usual=$4 versions=$5 feedback=$6

if [ "$db_mb" -gt $((VOLUME_MB * 70 / 100)) ]; then
  attn "volume: the database holds $db_mb MB of $VOLUME_MB MB. Grow the volume before it fills: a full one cannot even delete"
else
  ok "volume: the database holds $db_mb MB of $VOLUME_MB MB"
fi

if [ "$usage_mb" -ge "$BUDGET_MB" ]; then
  attn "usage data: the budget is full ($usage_mb MB), so new events are dropped. Delete junk, or wait for the 90-day expiry"
elif [ "$usage_mb" -gt $((BUDGET_MB * 70 / 100)) ]; then
  attn "usage data: $usage_mb MB of the $BUDGET_MB MB budget"
else
  ok "usage data: $usage_mb MB of the $BUDGET_MB MB budget"
fi

# Ten thousand events a day is far above today's traffic, so a quiet week does not make an
# ordinary day look like a flood.
if [ "$events" -gt 10000 ] && [ "$events" -gt $((usual * 5)) ]; then
  attn "usage data: $events events in 24 hours, against $usual a day before. Probably junk"
else
  ok "usage data: $events events in 24 hours ($usual a day before)"
fi

if [ "$versions" -gt 10 ]; then
  attn "usage data: $versions app versions in 24 hours. Invented versions are junk"
else
  ok "usage data: $versions app version(s) in 24 hours"
fi

if [ "$feedback" -gt 50 ]; then
  attn "feedback: $feedback messages in 24 hours. Probably spam; see tools/feedback/feedback.sh"
elif [ "$feedback" -gt 0 ]; then
  ok "feedback: $feedback new in 24 hours (tools/feedback/feedback.sh reads them)"
else
  ok "feedback: nothing new"
fi

exit "$attention"
