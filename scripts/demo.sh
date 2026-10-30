#!/usr/bin/env bash
# End-to-end demo of the main flows with curl, against a fresh local stack.
# Note: resets the local demo database (docker compose down -v) so every run starts from the same seed data.
# The live failure scenario (kill -9 between commit and publish) is in scripts/crash-demo.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080
step() { printf '\n\033[1m=== %s\033[0m\n' "$1"; }
pretty() { python3 -m json.tool; }
token() {
  curl -s "$API/dev/token" -H 'Content-Type: application/json' \
    -d "{\"sub\":\"$1\",\"tenant\":\"$2\",\"roles\":[\"$3\"]}" | sed -E 's/.*"token":"([^"]+)".*/\1/'
}
events() { curl -s "$API/api/v1/events" -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' "$@"; }
employee() { curl -s "$API/api/v1/employees/$1" -H "Authorization: Bearer $2"; }

step "0. Fresh start: docker compose up (app + Postgres, seeded with ACME and GLOBEX)"
docker compose down -v > /dev/null 2>&1 || true
docker compose up -d --build
until curl -sf "$API/actuator/health" > /dev/null; do sleep 1; done
HR=$(token acme-hr ACME HR_SYSTEM)
ADMIN=$(token acme-admin ACME TRANSPORT_ADMIN)
OPS=$(token acme-ops ACME OPERATOR)
GLOBEX_ADMIN=$(token globex-admin GLOBEX TRANSPORT_ADMIN)
echo "Tokens issued for ACME (HR_SYSTEM, TRANSPORT_ADMIN, OPERATOR) and GLOBEX (TRANSPORT_ADMIN)"

step "1. HR pushes a batch: 2 valid creates, bad phone, unknown office, v3 transfer, then the older v2, unknown deactivate"
events --data @samples/events-batch.json | pretty

step "2. The same batch replayed: nothing changes (idempotent)"
events --data @samples/events-batch.json | pretty

step "3. E2001 ended on v3 (HYD-01, S2200): the late v2 was ignored as stale"
employee E2001 "$ADMIN" | pretty

step "4. Tenant isolation: GLOBEX asking for ACME's employee gets 404"
employee E2001 "$GLOBEX_ADMIN" | pretty

step "5. Field ownership: HR moves E1001 (pickup 'Gate 3, Tower A' set by transport) to another office"
events --data '[{"eventId":"evt-7","type":"UPDATE","employeeId":"E1001","version":6,"occurredAt":"2026-10-30T10:00:00Z","hr":{"name":"Employee 1001","email":"e1001@acme.test","phone":"+919800001001","managerId":"E1005","officeCode":"BLR-02","shift":"S0900"}}]' | pretty
echo "-> pickup point kept (HR can't touch it) and flagged for review:"
employee E1001 "$ADMIN" | pretty

step "6. Transport admin updates the pickup point (clears the review flag)"
curl -s -X PUT "$API/api/v1/employees/E1001/transport" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"pickupPoint":"Whitefield Gate 2","specialAssistance":null}' | pretty

step "7. Nightly file (full): E1002 moves office, E1006 joins, E1001 row is older than the API change (stale)"
curl -s "$API/api/v1/reconciliations" -H "Authorization: Bearer $HR" \
  -F file=@samples/nightly-full.csv -F snapshotAt=2026-10-30T22:00:00Z | pretty

step "8. Nightly file cut short (2 of 7 rows): the safety brake holds the run, nobody is deactivated"
RUN=$(curl -s "$API/api/v1/reconciliations" -H "Authorization: Bearer $HR" \
  -F file=@samples/nightly-truncated.csv -F snapshotAt=2026-10-31T22:00:00Z)
echo "$RUN" | pretty
RUN_ID=$(echo "$RUN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

step "9. The operator reviews the held run and rejects the broken file"
curl -s -X POST "$API/api/v1/reconciliations/$RUN_ID/reject" -H "Authorization: Bearer $OPS" | pretty

step "10. What on-call sees (Prometheus metrics, operator token)"
curl -s "$API/actuator/prometheus" -H "Authorization: Bearer $OPS" \
  | grep -E '^roster_(events_total|outbox_pending|outbox_lag_seconds|recon_runs_total|recon_brake_tripped_total)' || true

step "11. A uniform error with a correlation ID (no token)"
HEADERS=$(mktemp)
BODY=$(curl -s -D "$HEADERS" "$API/api/v1/employees/E1001")
grep -iE '^(HTTP|x-correlation-id)' "$HEADERS" || true
echo "$BODY" | pretty
rm -f "$HEADERS"
