#!/usr/bin/env bash
# One pass through every journey via the gateway. Usage: scripts/smoke.sh [gateway-url]
set -euo pipefail
G="${1:-http://localhost:8080}"
H=(-H 'content-type: application/json' -H "X-Client-Id: smoke-$$")
j() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }
wait_done() {  # poll async request status until terminal
  for _ in $(seq 1 60); do
    s=$(curl -fsS "${H[@]}" "$G/api/requests/$1")
    st=$(echo "$s" | j '["status"]')
    [[ "$st" != PENDING ]] && { echo "  $st $(echo "$s" | j '.get("outcomeType")') $(echo "$s" | j '.get("error","")')"; return; }
    sleep 0.5
  done; echo "  still PENDING"; }
submit() { curl -fsS "${H[@]}" -X "$1" "$G$2" ${3:+-d "$3"} | j '["requestId"]'; }

echo "login:";         curl -sS "${H[@]}" -XPOST "$G/api/login" -d '{"username":"alice","password":"pw"}' | head -c 120; echo
echo "login (bad):";   curl -sS -o /dev/null -w '  HTTP %{http_code}\n' "${H[@]}" -XPOST "$G/api/login" -d '{"username":"alice","password":"wrong-password"}'
echo "create user:";   R=$(curl -fsS "${H[@]}" -XPOST "$G/api/users" -d '{"email":"ada@example.com","firstName":"Ada","lastName":"Lovelace","dateOfBirth":"1990-12-10"}')
U=$(echo "$R" | j '["entityId"]'); wait_done "$(echo "$R" | j '["requestId"]')"
echo "get user:";      curl -fsS "${H[@]}" "$G/api/users/$U" | head -c 160; echo
echo "balance:";       curl -fsS "${H[@]}" "$G/api/accounts/ACC-$U/balances"; echo
echo "add funds:";     wait_done "$(submit POST "/api/accounts/ACC-$U/funds" '{"amount":125.50}')"
echo "add funds (declined):"; wait_done "$(submit POST "/api/accounts/ACC-$U/funds" '{"amount":20000}')"
echo "balance:";       curl -fsS "${H[@]}" "$G/api/accounts/ACC-$U/balances"; echo
echo "upsert profile:"; wait_done "$(submit PUT "/api/users/$U/profile" '{"displayName":"Ada L","city":"London","country":"UK"}')"
echo "get profile:";   curl -fsS "${H[@]}" "$G/api/users/$U/profile" | head -c 160; echo
echo "insights:";      RID=$(submit POST "/api/users/$U/insights" '{"period":"30d"}'); wait_done "$RID"
curl -fsS "${H[@]}" "$G/api/users/$U/insights/$RID" | head -c 200; echo
echo "update user:";   wait_done "$(submit PUT "/api/users/$U" '{"lastName":"King"}')"
echo "delete profile:"; wait_done "$(submit DELETE "/api/users/$U/profile")"
echo "delete user:";   wait_done "$(submit DELETE "/api/users/$U")"
echo "get user (gone):"; curl -sS -o /dev/null -w '  HTTP %{http_code}\n' "${H[@]}" "$G/api/users/$U"
