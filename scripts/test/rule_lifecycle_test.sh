#!/bin/sh
# Mock-backed lifecycle test for hotrouter.sh's iptables/ip rule management.
# Proves: no rule accumulation across cycles, and full teardown leaves ZERO residue
# (no ghost rules that could black-hole the hotspot).

set -u
ASSET="${1:?usage: rule_lifecycle_test.sh /path/to/hotrouter.sh}"
TMP="$(mktemp -d)"
STORE="$TMP/iptables"      # lines: TABLE|CHAIN|SPEC
IPSTORE="$TMP/iprules"     # lines: rule|<spec>
: > "$STORE"; : > "$IPSTORE"

TC_EXISTS=0          # do the system tetherctrl_* chains exist this run?
SL_TABLE_DEFAULT=1   # does table wlan0 have a default route?

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1 ($2)"; }
asserteq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "want=$3 got=$2"; fi; }

# ---- mock iptables ----
iptables() {
  tbl=filter; op=""; chain=""; spec=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -t) tbl="$2"; shift 2 ;;
      -C|-I|-A|-D|-S|-nL|-L)
        op="$1"; chain="$2"; shift 2
        case "$op" in
          -I|-A) case "${1:-}" in ''|*[!0-9]*) : ;; *) shift ;; esac ;;
        esac
        spec="$*"; shift $# 2>/dev/null || set -- ;;
      *) shift ;;
    esac
  done
  key="$tbl|$chain|$spec"
  case "$op" in
    -nL|-L)
      case "$chain" in
        tetherctrl_nat_POSTROUTING|tetherctrl_FORWARD|tetherctrl_counters)
          [ "$TC_EXISTS" = 1 ] ; return $? ;;
        *) return 0 ;;
      esac ;;
    -C) grep -qxF "$key" "$STORE" ; return $? ;;
    -I|-A) echo "$key" >> "$STORE" ; return 0 ;;   # real iptables always inserts
    -D) awk -v k="$key" 'BEGIN{d=0} $0==k && !d {d=1; next} {print}' "$STORE" > "$STORE.t"
        mv "$STORE.t" "$STORE" ; return 0 ;;
    *) return 0 ;;
  esac
}

# ---- mock ip ----
ip() {
  sub="$1"; shift
  case "$sub" in
    rule)
      case "${1:-}" in
        add) shift; echo "rule|$*" >> "$IPSTORE" ;;
        del) shift
             awk -v k="rule|$*" 'BEGIN{d=0} $0==k && !d {d=1; next} {print}' "$IPSTORE" > "$IPSTORE.t"
             mv "$IPSTORE.t" "$IPSTORE" ;;
        ''|*)  # list
             while IFS= read -r l; do
               case "$l" in rule\|*) printf '17999:\t%s\n' "${l#rule|}" ;; esac
             done < "$IPSTORE" ;;
      esac ;;
    route)
      case "${1:-}" in
        show) if [ "${2:-}" = table ]; then
                [ "$SL_TABLE_DEFAULT" = 1 ] && echo "default via 100.64.0.1 dev wlan0"
              fi ;;
        flush) : ;;
      esac ;;
    link) return 0 ;;
  esac
}
ping() { return 0; }   # pretend Starlink reachable when probed directly

# ---- load the daemon's functions (everything before the CMD dispatch) ----
awk '/^CMD=/{exit} {print}' "$ASSET" > "$TMP/funcs.sh"
. "$TMP/funcs.sh"

# silence side-effecting helpers (they write to /data/local/tmp & /proc)
log() { :; }
logblock() { cat >/dev/null; }
write_state() { :; }
trim_log() { :; }
dump_diag() { :; }

total() { echo $(( $(wc -l < "$STORE") + $(wc -l < "$IPSTORE") )); }
nrules() { grep -cxF "$1" "$STORE"; }
niprule() { grep -c "iif wlan2 lookup wlan0" "$IPSTORE"; }

SELF_NAT="nat|POSTROUTING|-o wlan0 -j MASQUERADE"
SELF_FWD1="filter|FORWARD|-i wlan2 -o wlan0 -j ACCEPT"
SELF_FWD2="filter|FORWARD|-i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT"

echo "== Scenario 1: self-managed only (no tetherctrl) =="
TC_EXISTS=0
apply_starlink 2>/dev/null
asserteq "ip rule present once"     "$(niprule)"        1
asserteq "nat MASQUERADE once"      "$(nrules "$SELF_NAT")"  1
asserteq "fwd wlan2->wlan0 once"    "$(nrules "$SELF_FWD1")" 1
asserteq "fwd wlan0->wlan2 once"    "$(nrules "$SELF_FWD2")" 1
asserteq "no tetherctrl rules"      "$(grep -c tetherctrl "$STORE")" 0
asserteq "total = 4"                "$(total)"          4

echo "== Scenario 2: 50 keepalive passes must NOT accumulate =="
i=0; while [ $i -lt 50 ]; do keepalive_starlink 2>/dev/null; i=$((i+1)); done
asserteq "ip rule still once"       "$(niprule)"        1
asserteq "nat still once"           "$(nrules "$SELF_NAT")"  1
asserteq "total still 4"            "$(total)"          4

echo "== Scenario 3: 10 re-transitions to starlink must NOT accumulate =="
i=0; while [ $i -lt 10 ]; do apply_starlink 2>/dev/null; i=$((i+1)); done
asserteq "ip rule still once"       "$(niprule)"        1
asserteq "total still 4"            "$(total)"          4

echo "== Scenario 4: fallback to 4G purges everything =="
apply_4g
asserteq "zero residue after 4G"    "$(total)"          0

echo "== Scenario 5: tetherctrl chains exist, but Starlink path must NOT touch them =="
TC_EXISTS=1
apply_starlink 2>/dev/null
asserteq "still only 4 self rules"     "$(total)"  4
asserteq "zero tetherctrl rules added" "$(grep -c tetherctrl "$STORE")" 0
i=0; while [ $i -lt 30 ]; do keepalive_starlink 2>/dev/null; i=$((i+1)); done
asserteq "still 4 after 30 keepalives" "$(total)"  4
apply_4g
asserteq "4G purges to zero"           "$(total)"  0

echo "== Scenario 6: crash recovery — self + ip rule ghosts, then startup baseline =="
: > "$STORE"; : > "$IPSTORE"
apply_starlink 2>/dev/null
# simulate a SIGKILL'd run that left duplicate self rules + extra diversion ip rules
echo "$SELF_NAT" >> "$STORE"
echo "$SELF_FWD1" >> "$STORE"
echo "rule|from all iif wlan2 lookup wlan0 priority 17999" >> "$IPSTORE"
echo "rule|from all iif wlan2 lookup wlan0 priority 17999" >> "$IPSTORE"
[ "$(total)" -gt 4 ] && ok "ghosts present before baseline ($(total))" || bad "ghost setup" "got $(total)"
apply_4g    # what the startup baseline runs
asserteq "baseline purges self + ip ghosts to zero" "$(total)"  0

echo "== Scenario 7: do_stop-style purge from a clean starlink state =="
TC_EXISTS=0
apply_starlink 2>/dev/null
purge_footprint
asserteq "stop leaves zero residue" "$(total)"          0

echo
echo "$PASS passed, $FAIL failed"
rm -rf "$TMP"
[ "$FAIL" -eq 0 ]
