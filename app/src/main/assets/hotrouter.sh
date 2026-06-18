#!/system/bin/sh

# hotrouter.sh
#
# Automatic router daemon for the multimedia hotspot.
#
# Goal:
# - Prefer the external Starlink uplink (wlan0) for hotspot clients.
# - Fall back to the OEM 4G route (vlan13) when Starlink is unavailable.
#
# Design notes (see docs/DESIGN.md):
# - The Starlink path is fully self-managed: ip_forward + one `ip rule` diversion + our own
#   FORWARD/MASQUERADE rules. It does NOT touch the system tetherctrl_* chains at all.
#   (The original multimidia app rode on tetherctrl_*, which Android only populates while a
#   cellular upstream is alive — so Starlink broke whenever 4G dropped to zero.) The 4G
#   fallback still uses the system's own tetherctrl NAT, present whenever cellular is up.
# - Switching is debounced (hysteresis) and routing is only re-applied on an actual
#   transition, so brief Starlink ping blips no longer flap the route and reset live
#   connections (which used to kill CarPlay mid-drive).
# - On every transition it dumps a DIAG block (rules, routes, iptables, ping) so failures
#   on the open road can be diagnosed after the fact.
#
# Usage:
#   sh hotrouter.sh start   # run the routing loop (default)
#   sh hotrouter.sh stop    # kill the daemon and tear down all rules

BASE="/data/local/tmp"
NAME="hotrouter"
LOG="$BASE/$NAME.log"
PIDFILE="$BASE/$NAME.pid"
STATEFILE="$BASE/$NAME.state"
HOTSPOT_IF="wlan2"
STARLINK_IF="wlan0"
STARLINK_TABLE="wlan0"
RULE_PRIO="17999"
CHECK_HOSTS="8.8.8.8 1.1.1.1"
INTERVAL_SEC=5
MAX_LOG_LINES=1200

# Hysteresis: how many consecutive samples (INTERVAL_SEC apart) before switching.
UP_THRESHOLD=2     # ~10s of good Starlink before diverting to it
DOWN_THRESHOLD=4   # ~20s of bad Starlink before falling back to 4G
HEARTBEAT_EVERY=24

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2" >> "$LOG"
}

# Prefix every line of stdin with a DIAG tag (for multi-line command output).
logblock() {
  _t="$1"
  while IFS= read -r _l; do
    log DIAG "$_t| $_l"
  done
}

write_state() {
  echo "$1|$(date +%s)" > "$STATEFILE"
}

trim_log() {
  [ -f "$LOG" ] || return
  lines="$(wc -l < "$LOG" 2>/dev/null)"
  [ "$lines" -gt "$MAX_LOG_LINES" ] || return
  tail -n "$MAX_LOG_LINES" "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}

kill_old_hotrouters() {
  self="$$"
  # Kill the recorded daemon pid first. Toybox `ps` does not print script args, so a
  # `ps | grep hotrouter.sh` sweep never matches the setsid'd daemon — the pidfile and a
  # /proc/<pid>/cmdline scan are the only reliable ways to find it.
  if [ -f "$PIDFILE" ]; then
    oldpid="$(cat "$PIDFILE" 2>/dev/null)"
    if [ -n "$oldpid" ] && [ "$oldpid" != "$self" ]; then
      kill -9 "$oldpid" 2>/dev/null
    fi
  fi
  for p in /proc/[0-9]*; do
    pid="${p#/proc/}"
    [ "$pid" = "$self" ] && continue
    # Read via cat so a process exiting mid-scan (cmdline gone) is swallowed by 2>/dev/null
    # instead of leaking a shell "can't open" error to stderr.
    cmd="$(cat "$p/cmdline" 2>/dev/null | tr '\0' ' ')"
    case "$cmd" in
      *hotrouter.sh*start*) kill -9 "$pid" 2>/dev/null ;;
    esac
  done
  rm -f "$PIDFILE"
}

# ---- routing rule (divert hotspot ingress to the Starlink table) ----

cleanup_duplicate_rules() {
  while ip rule | grep -q "iif $HOTSPOT_IF lookup $STARLINK_TABLE"; do
    ip rule del from all iif "$HOTSPOT_IF" lookup "$STARLINK_TABLE" priority "$RULE_PRIO" 2>/dev/null || break
  done
}

# Idempotent: add the diversion rule only if absent (no delete-then-add churn, which would
# momentarily drop the rule on every steady-state pass).
ensure_rule_once() {
  ip rule | grep -q "iif $HOTSPOT_IF lookup $STARLINK_TABLE" && return 0
  ip rule add from all iif "$HOTSPOT_IF" lookup "$STARLINK_TABLE" priority "$RULE_PRIO" 2>/dev/null
}

# ---- self-managed NAT/forward (independent of the system tetherctrl chains) ----

ensure_iptables_self() {
  iptables -t nat -C POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null || \
    iptables -t nat -I POSTROUTING 1 -o "$STARLINK_IF" -j MASQUERADE

  iptables -C FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null || \
    iptables -I FORWARD 1 -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT

  iptables -C FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    iptables -I FORWARD 1 -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT
}

teardown_iptables_self() {
  while iptables -t nat -C POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null; do
    iptables -t nat -D POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null || break
  done
  while iptables -C FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null; do
    iptables -D FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null || break
  done
  while iptables -C FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; do
    iptables -D FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || break
  done
}

# ---- Starlink reachability probe ----

starlink_has_ping() {
  ip link show "$HOTSPOT_IF" >/dev/null 2>&1 || return 1
  ip link show "$STARLINK_IF" >/dev/null 2>&1 || return 1
  ip route show table "$STARLINK_TABLE" | grep -q "^default" || return 1

  for host in $CHECK_HOSTS; do
    ping -I "$STARLINK_IF" -c 1 -W 2 "$host" >/dev/null 2>&1 && return 0
  done

  return 1
}

# ---- apply / teardown a whole mode ----

apply_starlink() {
  echo 1 > /proc/sys/net/ipv4/ip_forward
  cleanup_duplicate_rules
  ensure_rule_once
  ensure_iptables_self
}

# Keep Starlink rules healthy between transitions WITHOUT flushing the route cache
# (flushing resets live connections). Every call here is an idempotent no-op when present.
keepalive_starlink() {
  ensure_rule_once
  ensure_iptables_self
}

# Remove every rule this daemon adds — the diversion ip rule and the self-managed
# NAT/forward. Each teardown is a `while -C ... ; do -D` loop, so any duplicate left by a
# crashed run is fully removed, not just one. Single source of truth for "our footprint",
# used by the 4G path, stop, the signal trap, and the startup baseline — so no exit path
# can leave a ghost rule behind that would black-hole hotspot traffic. (The dangerous one
# is the ip rule diversion; if it's gone, leftover ACCEPT/MASQUERADE rules route nothing.)
purge_footprint() {
  cleanup_duplicate_rules
  teardown_iptables_self
}

apply_4g() {
  purge_footprint
}

dump_diag() {
  log DIAG "===== diag begin ($1) ====="
  ip rule 2>&1 | logblock "iprule"
  ip route show table "$STARLINK_TABLE" 2>&1 | logblock "sltable"
  ip route show 2>&1 | logblock "main"
  iptables -t nat -S POSTROUTING 2>&1 | logblock "natPOST"
  iptables -t nat -S tetherctrl_nat_POSTROUTING 2>&1 | logblock "natTC"
  iptables -S FORWARD 2>&1 | logblock "fwd"
  iptables -S tetherctrl_FORWARD 2>&1 | logblock "fwdTC"
  for h in $CHECK_HOSTS; do
    if ping -I "$STARLINK_IF" -c 1 -W 2 "$h" >/dev/null 2>&1; then
      log DIAG "ping| $h via $STARLINK_IF OK"
    else
      log DIAG "ping| $h via $STARLINK_IF FAIL"
    fi
  done
  log DIAG "===== diag end ====="
}

do_stop() {
  # kill_old_hotrouters kills the daemon via pidfile + /proc cmdline scan (toybox ps
  # does not show script args, so a ps-based sweep can't find the setsid'd daemon).
  kill_old_hotrouters
  purge_footprint
  ip route flush cache
  write_state "OFF"
  log INFO "Service stopped + teardown done"
}

CMD="${1:-start}"

case "$CMD" in
  stop)
    do_stop
    exit 0
    ;;
  start)
    ;;
  *)
    echo "usage: $0 {start|stop}"
    exit 1
    ;;
esac

kill_old_hotrouters

echo $$ > "$PIDFILE"

# On a graceful kill (TERM/INT), purge every rule we added before leaving, so we never
# strand a diversion that black-holes the hotspot. (A SIGKILL can't be trapped — that case
# is covered by the startup baseline purge in the next launch.) The bare EXIT trap is a
# last-resort pidfile cleanup for any other exit.
trap 'purge_footprint; ip route flush cache; rm -f "$PIDFILE"; write_state "OFF"; log INFO "Service stopped (footprint purged)"; exit 0' INT TERM
trap 'rm -f "$PIDFILE"' EXIT

echo 1 > /proc/sys/net/ipv4/ip_forward

log INFO "Service force-started"
log INFO "Hotspot=$HOTSPOT_IF | Starlink=$STARLINK_IF | Table=$STARLINK_TABLE | Ping=$CHECK_HOSTS"
log INFO "Hysteresis up=$UP_THRESHOLD down=$DOWN_THRESHOLD interval=${INTERVAL_SEC}s"

# Clean baseline: start on 4G, no diversion. Hysteresis governs switches from here.
apply_4g
current="4g"
write_state "4G"
log INFO "Baseline mode=4G (clean)"
dump_diag "startup"

ok=0
fail=0
tick=0

while true; do
  trim_log

  if starlink_has_ping; then
    ok=$((ok + 1))
    fail=0
  else
    fail=$((fail + 1))
    ok=0
  fi

  want="$current"
  if [ "$current" != "starlink" ] && [ "$ok" -ge "$UP_THRESHOLD" ]; then
    want="starlink"
  fi
  if [ "$current" = "starlink" ] && [ "$fail" -ge "$DOWN_THRESHOLD" ]; then
    want="4g"
  fi

  if [ "$want" != "$current" ]; then
    if [ "$want" = "starlink" ]; then
      log INFO "Transition 4G -> STARLINK (ok_streak=$ok). Hotspot diverted through $STARLINK_IF."
      apply_starlink
      current="starlink"
      ip route flush cache
      write_state "STARLINK"
      dump_diag "to-starlink"
    else
      log WARN "Transition STARLINK -> 4G (fail_streak=$fail). Starlink ping lost."
      apply_4g
      current="4g"
      ip route flush cache
      write_state "4G"
      dump_diag "to-4g"
    fi
  else
    # Steady state: keep rules healthy, no cache flush, no route churn.
    if [ "$current" = "starlink" ]; then
      keepalive_starlink
      write_state "STARLINK"
    else
      write_state "4G"
    fi
  fi

  tick=$((tick + 1))
  if [ "$tick" -ge "$HEARTBEAT_EVERY" ]; then
    log INFO "Heartbeat: mode=$current ok=$ok fail=$fail"
    tick=0
  fi

  sleep "$INTERVAL_SEC"
done
