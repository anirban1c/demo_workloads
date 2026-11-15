#!/usr/bin/env bash
# Inject / clear vendor faults in the WireMock SaaS mocks at runtime (admin API).
#
#   scripts/faults.sh latency <vendor> <delay_ms> [pct=100]   # add delay to pct% of calls
#   scripts/faults.sh errors  <vendor> <pct> [status=503]     # fail pct% of calls
#   scripts/faults.sh outage  <vendor>                         # 100% connection resets
#   scripts/faults.sh reset   [vendor]                         # remove injected faults
#   scripts/faults.sh status                                   # list injected faults
#
# vendors: acme-identity paylane corebank kycheck relatecrm insight-ai
# Selection is per HTTP attempt: services send a random UUID in X-Attempt-Id and fault stubs match a share of the
# hex prefix space, so "10%" means ~10% of attempts (and a retry is an independent draw).
# Latency stubs proxy back into WireMock (minus X-Attempt-Id) so the real stub still produces the response.
set -euo pipefail
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8089}"

declare -A PREFIX=([acme-identity]=identity [paylane]=paylane [corebank]=corebank [kycheck]=kyc [relatecrm]=crm [insight-ai]=insights-ai)

die() { echo "error: $*" >&2; exit 1; }
prefix() { [[ -n "${PREFIX[$1]:-}" ]] || die "unknown vendor '$1' (one of: ${!PREFIX[*]})"; echo "${PREFIX[$1]}"; }

# Regex over the first two hex chars of X-Attempt-Id matching ~pct% of the 256 values.
pct_regex() {
  local pct=$1 n full rem parts=()
  n=$(( (pct * 256 + 50) / 100 )); (( n < 1 )) && n=1; (( n > 256 )) && n=256
  (( n == 256 )) && { echo '^.*'; return; }
  full=$(( n / 16 )); rem=$(( n % 16 ))
  (( full > 0 )) && parts+=("[0-$(printf %x $((full - 1)))][0-9a-f]")
  (( rem > 0 )) && parts+=("$(printf %x "$full")[0-$(printf %x $((rem - 1)))]")
  local IFS='|'; echo "^(${parts[*]}).*"
}

post() { curl -fsS -X POST "$WIREMOCK_URL/__admin/mappings" -H 'content-type: application/json' -d "$1" >/dev/null; }

reset_vendor() {
  curl -fsS "$WIREMOCK_URL/__admin/mappings" | python3 -c '
import json,sys
v=sys.argv[1]
for m in json.load(sys.stdin).get("mappings",[]):
    md=m.get("metadata",{}).get("fault")
    if md and (v=="*" or md.get("vendor")==v): print(m["id"])' "$1" |
  while read -r id; do curl -fsS -X DELETE "$WIREMOCK_URL/__admin/mappings/$id" >/dev/null; done
}

cmd="${1:-}"; shift || true
case "$cmd" in
  latency)
    v="${1:?vendor}"; delay="${2:?delay_ms}"; pct="${3:-100}"; p=$(prefix "$v"); re=$(pct_regex "$pct")
    post "{\"priority\":1,\"request\":{\"method\":\"ANY\",\"urlPathPattern\":\"/$p/.*\",
           \"headers\":{\"X-Attempt-Id\":{\"matches\":\"$re\"}}},
           \"response\":{\"proxyBaseUrl\":\"http://localhost:8080\",\"removeProxyRequestHeaders\":[\"X-Attempt-Id\"],
                         \"fixedDelayMilliseconds\":$delay},
           \"metadata\":{\"fault\":{\"vendor\":\"$v\",\"kind\":\"latency\",\"delayMs\":$delay,\"pct\":$pct}}}"
    echo "injected: $v +${delay}ms on ~${pct}% of calls" ;;
  errors)
    v="${1:?vendor}"; pct="${2:?pct}"; status="${3:-503}"; p=$(prefix "$v"); re=$(pct_regex "$pct")
    post "{\"priority\":1,\"request\":{\"method\":\"ANY\",\"urlPathPattern\":\"/$p/.*\",
           \"headers\":{\"X-Attempt-Id\":{\"matches\":\"$re\"}}},
           \"response\":{\"status\":$status,\"headers\":{\"Content-Type\":\"application/json\"},
                         \"jsonBody\":{\"error\":\"injected_fault\",\"vendor\":\"$v\"}},
           \"metadata\":{\"fault\":{\"vendor\":\"$v\",\"kind\":\"errors\",\"status\":$status,\"pct\":$pct}}}"
    echo "injected: $v HTTP $status on ~${pct}% of calls" ;;
  outage)
    v="${1:?vendor}"; p=$(prefix "$v")
    post "{\"priority\":1,\"request\":{\"method\":\"ANY\",\"urlPathPattern\":\"/$p/.*\"},
           \"response\":{\"fault\":\"CONNECTION_RESET_BY_PEER\"},
           \"metadata\":{\"fault\":{\"vendor\":\"$v\",\"kind\":\"outage\"}}}"
    echo "injected: $v full outage" ;;
  reset)
    v="${1:-*}"; reset_vendor "$v"; echo "reset: $v" ;;
  status)
    curl -fsS "$WIREMOCK_URL/__admin/mappings" | python3 -c '
import json,sys
f=[m["metadata"]["fault"] for m in json.load(sys.stdin).get("mappings",[]) if "fault" in m.get("metadata",{})]
print(json.dumps(f,indent=1) if f else "no injected faults")' ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
