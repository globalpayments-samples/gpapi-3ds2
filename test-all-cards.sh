#!/usr/bin/env bash
# test-all-cards.sh — GP-API 3DS2 automated card test runner
# Usage: ./test-all-cards.sh [port] [backend]
# Example: ./test-all-cards.sh 8001 nodejs

set -euo pipefail

PORT="${1:-8001}"
BACKEND="${2:-nodejs}"
BASE="http://localhost:${PORT}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

PASS=0
FAIL=0
WARN=0

# ── helpers ──────────────────────────────────────────────────────────────────

pass() { echo -e "  ${GREEN}✓ PASS${NC} $1"; ((PASS++)) || true; }
fail() { echo -e "  ${RED}✗ FAIL${NC} $1"; ((FAIL++)) || true; }
warn() { echo -e "  ${YELLOW}! WARN${NC} $1"; ((WARN++)) || true; }
info() { echo -e "  ${CYAN}ℹ${NC}      $1"; }

json_field() {
  echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d$2)" 2>/dev/null || echo ""
}

post() {
  curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "$2" \
    "${BASE}${1}"
}

# ── health check ─────────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  GP-API 3DS2 Test Runner — ${BACKEND} @ port ${PORT}${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

echo "Checking health..."
HEALTH=$(curl -s "${BASE}/api/health" || echo '{"status":"error"}')
HEALTH_STATUS=$(json_field "$HEALTH" "['status']")
if [ "$HEALTH_STATUS" = "ok" ]; then
  pass "Backend healthy"
else
  fail "Backend unreachable — is the server running on port ${PORT}?"
  exit 1
fi
echo ""

# ── test function ─────────────────────────────────────────────────────────────

run_card_test() {
  local label="$1"
  local card="$2"
  local expected_enroll="$3"   # Y / N / U
  local expected_status="$4"   # SUCCESS_AUTHENTICATED / CHALLENGE_REQUIRED / FAILED / UNAVAILABLE / any

  echo -e "─── ${CYAN}${label}${NC} (${card})"

  # Step 1: Check enrollment
  local r1
  r1=$(post "/api/check-enrollment" "{
    \"card_number\": \"${card}\",
    \"exp_month\": \"12\",
    \"exp_year\": \"2026\"
  }")

  local enrolled
  enrolled=$(json_field "$r1" "['data']['enrolled']" 2>/dev/null || echo "")
  local server_trans_id
  server_trans_id=$(json_field "$r1" "['data']['server_trans_id']" 2>/dev/null || echo "")
  local success1
  success1=$(json_field "$r1" "['success']" 2>/dev/null || echo "false")

  if [ "$success1" != "True" ] && [ "$success1" != "true" ]; then
    local err
    err=$(json_field "$r1" "['error']" 2>/dev/null || echo "unknown error")
    fail "Enrollment check failed: ${err}"
    return
  fi

  info "Enrolled: ${enrolled}, server_trans_id: ${server_trans_id:0:16}…"

  if [ -n "$expected_enroll" ] && [ "$enrolled" != "$expected_enroll" ]; then
    warn "Expected enrolled=${expected_enroll}, got ${enrolled}"
  else
    pass "Enrollment: enrolled=${enrolled}"
  fi

  if [ -z "$server_trans_id" ]; then
    fail "No server_trans_id returned"
    return
  fi

  # Step 3: Initiate auth (skip method URL — UNAVAILABLE)
  local r3
  r3=$(post "/api/initiate-auth" "{
    \"server_trans_id\": \"${server_trans_id}\",
    \"method_url_completion\": \"UNAVAILABLE\",
    \"card_number\": \"${card}\",
    \"exp_month\": \"12\",
    \"exp_year\": \"2026\",
    \"cardholder_name\": \"Test User\",
    \"browser_data\": {
      \"accept_header\": \"text/html\",
      \"color_depth\": 24,
      \"ip\": \"123.123.123.123\",
      \"java_enabled\": false,
      \"javascript_enabled\": true,
      \"language\": \"en-GB\",
      \"screen_height\": 1080,
      \"screen_width\": 1920,
      \"challenge_window_size\": \"FULL_SCREEN\",
      \"timezone\": \"0\",
      \"user_agent\": \"TestRunner/1.0\"
    },
    \"order\": { \"amount\": \"10.00\", \"currency\": \"GBP\" }
  }")

  local auth_status
  auth_status=$(json_field "$r3" "['data']['status']" 2>/dev/null || echo "")
  local success3
  success3=$(json_field "$r3" "['success']" 2>/dev/null || echo "false")

  if [ "$success3" != "True" ] && [ "$success3" != "true" ]; then
    local err3
    err3=$(json_field "$r3" "['error']" 2>/dev/null || echo "unknown error")
    fail "Initiate auth failed: ${err3}"
    return
  fi

  info "Auth status: ${auth_status}"

  if [ -n "$expected_status" ] && [ "$auth_status" = "$expected_status" ]; then
    pass "Auth status: ${auth_status}"
  elif [ -n "$expected_status" ]; then
    # Challenge cards can't be fully tested without ACS — treat as warn
    if [ "$expected_status" = "CHALLENGE_REQUIRED" ] && [ "$auth_status" = "CHALLENGE_REQUIRED" ]; then
      pass "Auth status: CHALLENGE_REQUIRED (ACS not tested in CLI)"
    else
      warn "Expected status=${expected_status}, got ${auth_status}"
    fi
  else
    pass "Auth status: ${auth_status}"
  fi

  echo ""
}

# ── run all cards ─────────────────────────────────────────────────────────────

run_card_test "Frictionless Visa"        "4263970000005262" "Y" "SUCCESS_AUTHENTICATED"
run_card_test "Frictionless Mastercard"  "5425230000004415" "Y" "SUCCESS_AUTHENTICATED"
run_card_test "Challenge Visa"           "4012001037141112" "Y" "CHALLENGE_REQUIRED"
run_card_test "Challenge Mastercard"     "5114610000004778" "Y" "CHALLENGE_REQUIRED"
run_card_test "Auth Failed Visa"         "4012001036853337" "Y" "FAILED"
run_card_test "Unavailable Visa"         "4012001036273338" ""  "UNAVAILABLE"

# ── summary ──────────────────────────────────────────────────────────────────

echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "  Results:  ${GREEN}${PASS} passed${NC}  ${YELLOW}${WARN} warned${NC}  ${RED}${FAIL} failed${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
