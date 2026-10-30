#!/usr/bin/env bash
# Live crash demo: the app is killed (SIGKILL) after a change is committed but before its event is published.
# After a restart the event is still delivered, so E1001's future bookings get cancelled.
#
# The relay normally publishes within ~500 ms, too fast to kill by hand, so it starts paused to hold that window open.
# Note: this resets the local demo database (docker compose down -v) so E1001 starts ACTIVE every time.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080

step() { printf '\n=== %s\n' "$1"; }
sql() { docker compose exec -T db psql -U roster -d roster -c "$1"; }
wait_for_app() { until curl -sf "$API/actuator/health" > /dev/null; do sleep 1; done; }
show_state() {
  sql "SELECT employee_id, status, hr_version FROM employee WHERE tenant_id = 'ACME' AND employee_id = 'E1001';"
  sql "SELECT event_type, published_at IS NOT NULL AS published FROM outbox_event WHERE aggregate_id = 'E1001' ORDER BY id;"
  sql "SELECT b.id AS booking, t.pickup_at > now() AS future_trip, b.status, t.seats_booked
       FROM booking b JOIN trip t ON t.id = b.trip_id WHERE b.employee_id = 'E1001' ORDER BY t.pickup_at;"
}

step "1. Fresh start with the outbox relay PAUSED"
docker compose down -v > /dev/null 2>&1 || true
OUTBOX_RELAY_ENABLED=false docker compose up -d --build
wait_for_app

step "2. HR deactivates E1001 (2 future bookings, 1 past)"
TOKEN=$(curl -s "$API/dev/token" -H 'Content-Type: application/json' \
  -d '{"sub":"acme-hr","tenant":"ACME","roles":["HR_SYSTEM"]}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s "$API/api/v1/events" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '[{"eventId":"demo-1","type":"DEACTIVATE","employeeId":"E1001","version":6,"occurredAt":"2026-10-30T09:00:00Z"}]'
echo

step "3. Committed but NOT published: employee INACTIVE, event waiting, bookings still CONFIRMED"
show_state

step "4. CRASH: kill -9 the app, no graceful shutdown"
docker compose kill app

step "5. Restart the app with the relay running"
docker compose up -d app
wait_for_app
sleep 2

step "6. After restart: event published, future bookings CANCELLED, seats freed, past booking untouched"
show_state
docker compose logs app | grep -E 'Published EMPLOYEE_DEACTIVATED|Cancelled [0-9]+ future bookings' | tail -2 || true
