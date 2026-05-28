#!/usr/bin/env bash
# test-all-cards.sh — GP-API 3DS2 automated card test runner
# Usage: ./test-all-cards.sh [port] [backend]
# Example: ./test-all-cards.sh 8001 nodejs
#
# NOTE: CLI limitations —
#   - Hosted Fields iframe entry requires a browser.
#   - Device fingerprint (method URL) and ACS challenge completion require a browser.
#   - This script verifies backend SDK connectivity, tokenization-token creation,
#     enrollment, initiate-auth, result lookup, and sandbox authorization when the
#     authentication reaches a final frictionless state.

set -euo pipefail

PORT="${1:-8001}"
BACKEND="${2:-nodejs}"
BASE="http://localhost:${PORT}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

pass() { echo -e "  ${GREEN}✓ PASS${NC} $1"; ((PASS++)) || true; }
fail() { echo -e "  ${RED}✗ FAIL${NC} $1"; ((FAIL++)) || true; }
warn() { echo -e "  ${YELLOW}! WARN${NC} $1"; ((WARN++)) || true; }
info() { echo -e "  ${CYAN}ℹ${NC}      $1"; }

json_field() {
  echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d$2)" 2>/dev/null || echo ""
}

post() {
  curl -s -X POST -H "Content-Type: application/json" -d "$2" "${BASE}${1}"
}

get() {
  curl -s "${BASE}${1}"
}

is_true() {
  [ "$1" = "True" ] || [ "$1" = "true" ]
}

is_final_auth() {
  [ "$1" = "SUCCESS_AUTHENTICATED" ] || [ "$1" = "ATTEMPT_ACKNOWLEDGED" ]
}

# ── health check ─────────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  GP-API 3DS2 Test Runner — ${BACKEND} @ port ${PORT}${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

echo "Checking health..."
HEALTH=$(curl -s "${BASE}/api/health" || echo '{"status":"error"}')
if [ "$(json_field "$HEALTH" "['status']")" = "ok" ]; then
  pass "Backend healthy"
else
  fail "Backend unreachable — is the server running on port ${PORT}?"
  exit 1
fi
echo ""

echo "Checking Hosted Fields tokenization config..."
TOKEN_CONFIG=$(get "/api/tokenization-config" || echo '{"success":false}')
if is_true "$(json_field "$TOKEN_CONFIG" "['success']")"; then
  pass "Hosted Fields tokenization token generated"
else
  fail "Tokenization config failed: $(json_field "$TOKEN_CONFIG" "['error']")"
  exit 1
fi
echo ""

# ── test function ─────────────────────────────────────────────────────────────
# GP-API UCP enrollment values: ENROLLED / NOT_ENROLLED
# expected_enroll: ENROLLED / NOT_ENROLLED / "" (skip)

run_card_test() {
  local label="$1"
  local card="$2"
  local expected_enroll="$3"

  echo -e "─── ${CYAN}${label}${NC} (${card})"

  # Step 1: Check enrollment
  local r1
  r1=$(post "/api/check-enrollment" "{
    \"card_number\": \"${card}\",
    \"exp_month\": \"12\",
    \"exp_year\": \"2026\"
  }")

  local enrolled server_trans_id message_version success1
  enrolled=$(json_field "$r1" "['data']['enrolled']")
  server_trans_id=$(json_field "$r1" "['data']['server_trans_id']")
  message_version=$(json_field "$r1" "['data']['message_version']")
  success1=$(json_field "$r1" "['success']")

  if ! is_true "$success1"; then
    fail "Enrollment check failed: $(json_field "$r1" "['error']")"
    echo ""
    return
  fi

  info "Enrolled: ${enrolled}, id: ${server_trans_id:0:20}…, msg_ver: ${message_version}"

  if [ -n "$expected_enroll" ] && [ "$enrolled" != "$expected_enroll" ]; then
    warn "Expected enrolled=${expected_enroll}, got ${enrolled}"
  else
    pass "Enrollment: enrolled=${enrolled}"
  fi

  if [ -z "$server_trans_id" ]; then
    fail "No server_trans_id returned"
    echo ""
    return
  fi

  # Not enrolled — skip auth steps
  if [ "$enrolled" = "NOT_ENROLLED" ]; then
    pass "Card not enrolled — auth steps skipped (expected)"
    echo ""
    return
  fi

  # Step 3: Initiate auth
  local r3
  r3=$(post "/api/initiate-auth" "{
    \"server_trans_id\": \"${server_trans_id}\",
    \"message_version\": \"${message_version}\",
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

  local success3 auth_status
  success3=$(json_field "$r3" "['success']")
  auth_status=$(json_field "$r3" "['data']['status']")

  if ! is_true "$success3"; then
    fail "Initiate auth failed: $(json_field "$r3" "['error']")"
    echo ""
    return
  fi

  info "Initiate auth: status=${auth_status}"
  pass "Initiate auth: no error"

  if [ "$auth_status" = "CHALLENGE_REQUIRED" ]; then
    pass "Challenge required — browser ACS completion required"
    echo ""
    return
  fi

  if [ "$auth_status" = "AVAILABLE" ]; then
    warn "Authentication still AVAILABLE — browser method notification required before final result"
    echo ""
    return
  fi

  local r5 success5 result_status eci auth_value ds_trans_ref
  r5=$(post "/api/get-auth-result" "{
    \"server_trans_id\": \"${server_trans_id}\",
    \"amount\": \"10.00\"
  }")

  success5=$(json_field "$r5" "['success']")
  result_status=$(json_field "$r5" "['data']['status']")
  eci=$(json_field "$r5" "['data']['eci']")
  auth_value=$(json_field "$r5" "['data']['authentication_value']")
  ds_trans_ref=$(json_field "$r5" "['data']['ds_trans_ref']")

  if ! is_true "$success5"; then
    fail "Authentication result lookup failed: $(json_field "$r5" "['error']")"
    echo ""
    return
  fi

  info "Auth result: status=${result_status}, eci=${eci}"
  pass "Authentication result lookup"

  if ! is_final_auth "$result_status"; then
    pass "Payment skipped — authentication did not reach final authenticated/acknowledged state"
    echo ""
    return
  fi

  local r6 success6 transaction_id result_code payment_status
  r6=$(post "/api/authorize-payment" "{
    \"card_number\": \"${card}\",
    \"exp_month\": \"12\",
    \"exp_year\": \"2026\",
    \"cvn\": \"123\",
    \"cardholder_name\": \"Test User\",
    \"amount\": \"10.00\",
    \"currency\": \"GBP\",
    \"authentication_id\": \"${server_trans_id}\",
    \"three_ds\": {
      \"authentication_id\": \"${server_trans_id}\",
      \"authentication_value\": \"${auth_value}\",
      \"eci\": \"${eci}\",
      \"server_trans_ref\": \"${server_trans_id}\",
      \"ds_trans_ref\": \"${ds_trans_ref}\",
      \"message_version\": \"${message_version}\"
    }
  }")

  success6=$(json_field "$r6" "['success']")
  transaction_id=$(json_field "$r6" "['data']['transaction_id']")
  result_code=$(json_field "$r6" "['data']['result_code']")
  payment_status=$(json_field "$r6" "['data']['status']")

  if ! is_true "$success6"; then
    fail "Payment authorization failed: $(json_field "$r6" "['error']")"
    echo ""
    return
  fi

  info "Payment: result=${result_code}, status=${payment_status}, id=${transaction_id:0:24}…"
  pass "Payment authorization"

  echo ""
}

# ── run all cards ─────────────────────────────────────────────────────────────

run_card_test "Frictionless Visa"        "4263970000005262" "ENROLLED"
run_card_test "Frictionless Mastercard"  "5425230000004415" "ENROLLED"
run_card_test "Challenge Visa"           "4012001037141112" "ENROLLED"
run_card_test "Challenge Mastercard"     "5114610000004778" "ENROLLED"
run_card_test "Auth Failed Visa"         "4012001036853337" "NOT_ENROLLED"
run_card_test "Unavailable Visa"         "4012001036273338" "NOT_ENROLLED"

# ── summary ──────────────────────────────────────────────────────────────────

echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo -e "  Results:  ${GREEN}${PASS} passed${NC}  ${YELLOW}${WARN} warned${NC}  ${RED}${FAIL} failed${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
echo ""

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
