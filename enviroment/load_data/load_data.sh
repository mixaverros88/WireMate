#!/bin/bash

# =============================================================================
# WireMate — Sample Data Loader
# =============================================================================
# Populates WireMate with N projects, each containing N mocks, then exercises
# every API endpoint (clone, move, update, publish, delete, notifications).
#
# Usage:  bash load_data.sh [N] [BASE_URL] [WIREMOCK_URL] [HITS]
#         N              — number of projects AND mocks-per-project (default: 3)
#         BASE_URL       — WireMate backend URL            (default: http://localhost:8081)
#         WIREMOCK_URL   — WireMock server URL             (default: http://localhost:8080)
#         HITS           — times to hit each mock for logs (default: 100)
#
# Examples:
#   bash load_data.sh                      # 3 projects × 3 mocks, 100 hits each
#   bash load_data.sh 5                    # 5 projects × 5 mocks, 100 hits each
#   bash load_data.sh 3 http://my-host:8081 http://my-host:8080 50
# =============================================================================

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'HELP'
WireMate — Sample Data Loader

Populates WireMate with N projects, each containing N mocks, then exercises
every API endpoint (clone, move, update, publish, delete, notifications).
After creation, each mock is hit HITS times against WireMock to generate
request journal logs with diverse HTTP statuses (2xx, 4xx, 5xx).

Usage:
  bash load_data.sh [OPTIONS] [N] [BASE_URL] [WIREMOCK_URL] [HITS]

Arguments:
  N              Number of projects AND mocks-per-project   (default: 3)
  BASE_URL       WireMate backend API URL                   (default: http://localhost:8081)
  WIREMOCK_URL   WireMock server URL                        (default: http://localhost:8080)
  HITS           Times to hit each mock for log generation  (default: 100)

Options:
  -h, --help     Show this help message and exit

Examples:
  bash load_data.sh                                            # 3 projects × 3 mocks, 100 hits each
  bash load_data.sh 5                                          # 5 projects × 5 mocks, 100 hits each
  bash load_data.sh 3 http://my-host:8081 http://my-host:8080 50
HELP
  exit 0
fi

N="${1:-3}"
BASE_URL="${2:-http://localhost:8081}"

# ---------------------------------------------------------------------------
# Mock data pools — the script cycles through these
# ---------------------------------------------------------------------------
PROJECT_NAMES=("User Service" "Order Service" "Payment Gateway" "Notification Service" "Inventory Service" "Auth Service" "Search Service" "Analytics Service" "Shipping Service" "Billing Service")
HTTP_METHODS=("GET" "POST" "PUT" "DELETE" "PATCH" "GET" "POST" "GET" "DELETE" "PUT")
URL_PATHS=("/api/v1/users" "/api/v1/orders" "/api/v1/payments/charge" "/api/v1/notifications/email" "/api/v1/products" "/api/v1/auth/token" "/api/v1/search" "/api/v1/analytics/events" "/api/v1/shipments" "/api/v1/invoices")
STATUS_CODES=(200 201 400 202 404 401 201 500 204 403)

# Response bodies keyed by status code — gives realistic payloads per status
declare -A STATUS_BODIES
STATUS_BODIES[200]='{"success":true,"data":{"id":1,"status":"ok"}}'
STATUS_BODIES[201]='{"success":true,"data":{"id":1,"created":true}}'
STATUS_BODIES[202]='{"accepted":true,"message":"Request accepted for processing"}'
STATUS_BODIES[204]='{}'
STATUS_BODIES[400]='{"error":"Bad Request","message":"Validation failed: missing required field","code":"VALIDATION_ERROR"}'
STATUS_BODIES[401]='{"error":"Unauthorized","message":"Invalid or expired authentication token","code":"AUTH_REQUIRED"}'
STATUS_BODIES[403]='{"error":"Forbidden","message":"You do not have permission to access this resource","code":"ACCESS_DENIED"}'
STATUS_BODIES[404]='{"error":"Not Found","message":"The requested resource does not exist","code":"RESOURCE_NOT_FOUND"}'
STATUS_BODIES[500]='{"error":"Internal Server Error","message":"An unexpected error occurred","code":"SERVER_ERROR"}'

WIREMOCK_BASE_URL="${3:-http://localhost:8080}"
HITS_PER_MOCK="${4:-100}"

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------
TOTAL_PROJECTS=0
TOTAL_MOCKS=0
TOTAL_CLONED_PROJECTS=0
TOTAL_CLONED_MOCKS=0
TOTAL_UPDATED=0
TOTAL_PROJECT_UPDATED=0
TOTAL_MOVED=0
TOTAL_PUBLISHED=0
TOTAL_DELETED_MOCKS=0
TOTAL_DELETED_PROJECTS=0
TOTAL_HITS=0

# ---------------------------------------------------------------------------
# Arrays to store created IDs for later operations
# ---------------------------------------------------------------------------
declare -a PROJECT_IDS
declare -a ALL_MOCK_IDS
declare -a LAST_PROJECT_MOCK_IDS
declare -a MOCK_WIREMOCK_URLS
declare -a MOCK_WIREMOCK_METHODS

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
extract_id() {
  echo "$1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "============================================="
echo "  WireMate Data Loader"
echo "  Backend  : $BASE_URL"
echo "  WireMock : $WIREMOCK_BASE_URL"
echo "  N        : $N  (projects × mocks-per-project)"
echo "  Hits     : $HITS_PER_MOCK per mock"
echo "============================================="
echo ""

# =============================================================================
#  PHASE 1 — Create N projects  (POST /api/projects)
# =============================================================================
echo ">> PHASE 1: Creating $N projects"
echo "   -----------------------------------------"

for (( i=0; i<N; i++ )); do
  name="${PROJECT_NAMES[$((i % ${#PROJECT_NAMES[@]}))]}"
  # Append index if we cycle past the names pool
  if (( i >= ${#PROJECT_NAMES[@]} )); then
    name="$name $((i + 1))"
  fi

  response=$(curl -s -X POST "$BASE_URL/api/projects" \
    -H "Content-Type: application/json" \
    -d "{\"name\": \"$name\"}")
  id=$(extract_id "$response")
  PROJECT_IDS+=("$id")
  TOTAL_PROJECTS=$((TOTAL_PROJECTS + 1))
  echo "   [$TOTAL_PROJECTS] $name  ->  $id"
done
echo ""

# =============================================================================
#  PHASE 2 — Create N mocks per project  (POST /api/mocks)
# =============================================================================
echo ">> PHASE 2: Creating $N mocks per project ($((N * N)) total)"
echo "   -----------------------------------------"

mock_counter=0
for (( p=0; p<N; p++ )); do
  pid="${PROJECT_IDS[$p]}"
  LAST_PROJECT_MOCK_IDS=()
  echo "   Project: ${PROJECT_NAMES[$((p % ${#PROJECT_NAMES[@]}))]}  ($pid)"

  for (( m=0; m<N; m++ )); do
    idx=$(( (p * N + m) % ${#URL_PATHS[@]} ))
    method="${HTTP_METHODS[$idx]}"
    url_path="${URL_PATHS[$idx]}"
    status="${STATUS_CODES[$idx]}"
    mock_name="$method ${url_path##*/} #$((m+1))"
    description="Auto-generated mock $((mock_counter+1))"

    # Build WireMock request URL
    mock_url="${url_path}/mock-${mock_counter}"

    # Build WireMock request JSON
    req_json="{\"method\":\"$method\",\"url\":\"$mock_url\"}"

    # Build WireMock response JSON with status-appropriate body
    body="${STATUS_BODIES[$status]}"
    resp_json="{\"status\":$status,\"jsonBody\":$body,\"headers\":{\"Content-Type\":\"application/json\"}}"

    result=$(curl -s -X POST "$BASE_URL/api/mocks" \
      -H "Content-Type: application/json" \
      -d "{
        \"name\": \"$mock_name\",
        \"description\": \"$description\",
        \"projectId\": \"$pid\",
        \"request\": $req_json,
        \"response\": $resp_json,
        \"persistent\": true
      }")
    mid=$(extract_id "$result")
    ALL_MOCK_IDS+=("$mid")
    LAST_PROJECT_MOCK_IDS+=("$mid")
    MOCK_WIREMOCK_URLS+=("$mock_url")
    MOCK_WIREMOCK_METHODS+=("$method")
    TOTAL_MOCKS=$((TOTAL_MOCKS + 1))
    mock_counter=$((mock_counter + 1))
    echo "      [$TOTAL_MOCKS] $mock_name (HTTP $status)  ->  $mid"
  done
done
echo ""

# =============================================================================
#  PHASE 3 — Read operations (GET endpoints)
# =============================================================================
echo ">> PHASE 3: Read operations"
echo "   -----------------------------------------"

# GET /api/projects — list all projects
echo "   GET /api/projects (list all)"
curl -s -X GET "$BASE_URL/api/projects" > /dev/null
echo "      OK"

# GET /api/projects/{id} — get project with mocks
echo "   GET /api/projects/{id} (first project)"
curl -s -X GET "$BASE_URL/api/projects/${PROJECT_IDS[0]}" > /dev/null
echo "      OK  ->  ${PROJECT_IDS[0]}"

# GET /api/mocks/{id} — get single mock
echo "   GET /api/mocks/{id} (first mock)"
curl -s -X GET "$BASE_URL/api/mocks/${ALL_MOCK_IDS[0]}" > /dev/null
echo "      OK  ->  ${ALL_MOCK_IDS[0]}"

# Note: there is no separate "list mocks for project" endpoint.
# Mocks for a project are returned embedded in GET /api/projects/{id}.

# GET /api/backoffice/notifications — notifications (paginated)
echo "   GET /api/backoffice/notifications"
curl -s -X GET "$BASE_URL/api/backoffice/notifications" > /dev/null
echo "      OK"

echo ""

# =============================================================================
#  PHASE 4 — Update first mock of each project  (PUT /api/mocks/{id})
# =============================================================================
echo ">> PHASE 4: Updating first mock of each project"
echo "   -----------------------------------------"

for (( p=0; p<N; p++ )); do
  mock_idx=$((p * N))  # first mock of project p
  mid="${ALL_MOCK_IDS[$mock_idx]}"

  curl -s -X PUT "$BASE_URL/api/mocks/$mid" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"Updated Mock (Project $((p+1)))\",
      \"request\": {\"method\":\"GET\",\"url\":\"/api/v1/updated/endpoint-$p\"},
      \"response\": {\"status\":200,\"jsonBody\":{\"updated\":true},\"headers\":{\"Content-Type\":\"application/json\"}},
      \"persistent\": true
    }" > /dev/null

  TOTAL_UPDATED=$((TOTAL_UPDATED + 1))
  echo "   [$TOTAL_UPDATED] Updated mock $mid"
done
echo ""

# =============================================================================
#  PHASE 4b — Update first project's name  (PUT /api/projects/{id})
# =============================================================================
echo ">> PHASE 4b: Updating first project"
echo "   -----------------------------------------"

curl -s -X PUT "$BASE_URL/api/projects/${PROJECT_IDS[0]}" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"${PROJECT_NAMES[0]} (Renamed)\"}" > /dev/null
TOTAL_PROJECT_UPDATED=$((TOTAL_PROJECT_UPDATED + 1))
echo "   [$TOTAL_PROJECT_UPDATED] Updated project ${PROJECT_IDS[0]}"
echo ""

# =============================================================================
#  PHASE 5 — Re-publish mocks to WireMock  (POST /api/mocks/{id}/republish)
# =============================================================================
# Note: mocks are auto-published to WireMock on creation (POST /api/mocks).
# This phase exercises the manual re-publish endpoint.
echo ">> PHASE 5: Re-publishing first mock of each project to WireMock"
echo "   -----------------------------------------"

for (( p=0; p<N; p++ )); do
  mock_idx=$((p * N))
  mid="${ALL_MOCK_IDS[$mock_idx]}"

  curl -s -X POST "$BASE_URL/api/mocks/$mid/republish" > /dev/null

  TOTAL_PUBLISHED=$((TOTAL_PUBLISHED + 1))
  echo "   [$TOTAL_PUBLISHED] Re-published mock $mid"
done
echo ""

# =============================================================================
#  PHASE 5b — Hit every mock endpoint N times to generate WireMock logs
# =============================================================================
echo ">> PHASE 5b: Generating traffic — hitting each mock $HITS_PER_MOCK times"
echo "   -----------------------------------------"

total_mock_count=${#MOCK_WIREMOCK_URLS[@]}
for (( i=0; i<total_mock_count; i++ )); do
  url="${MOCK_WIREMOCK_URLS[$i]}"
  method="${MOCK_WIREMOCK_METHODS[$i]}"
  full_url="${WIREMOCK_BASE_URL}${url}"

  echo -n "   [$((i+1))/$total_mock_count] $method $url  "

  # Build curl args based on method
  case "$method" in
    GET|DELETE)
      curl_data_args=""
      ;;
    *)
      curl_data_args='-H "Content-Type: application/json" -d "{\"test\":true}"'
      ;;
  esac

  for (( h=0; h<HITS_PER_MOCK; h++ )); do
    case "$method" in
      GET|DELETE)
        curl -s -o /dev/null -X "$method" "$full_url"
        ;;
      *)
        curl -s -o /dev/null -X "$method" "$full_url" \
          -H "Content-Type: application/json" \
          -d '{"test":true}'
        ;;
    esac
    TOTAL_HITS=$((TOTAL_HITS + 1))
  done
  echo "($HITS_PER_MOCK hits)"
done
echo "   Total requests sent: $TOTAL_HITS"
echo ""

# =============================================================================
#  PHASE 6 — Clone first project  (POST /api/projects/{id}/clone)
# =============================================================================
echo ">> PHASE 6: Cloning first project"
echo "   -----------------------------------------"

clone_response=$(curl -s -X POST "$BASE_URL/api/projects/${PROJECT_IDS[0]}/clone" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"${PROJECT_NAMES[0]} (Clone)\"}")
cloned_project_id=$(extract_id "$clone_response")
TOTAL_CLONED_PROJECTS=$((TOTAL_CLONED_PROJECTS + 1))
echo "   Cloned project ${PROJECT_IDS[0]}  ->  $cloned_project_id"
echo ""

# =============================================================================
#  PHASE 7 — Clone first mock of second project  (POST /api/mocks/{id}/clone)
# =============================================================================
echo ">> PHASE 7: Cloning first mock of second project"
echo "   -----------------------------------------"

if (( N >= 2 )); then
  source_mock="${ALL_MOCK_IDS[$N]}"  # first mock of project index 1
  clone_mock_resp=$(curl -s -X POST "$BASE_URL/api/mocks/$source_mock/clone" \
    -H "Content-Type: application/json" \
    -d "{\"name\": \"Cloned Mock\"}")
  cloned_mock_id=$(extract_id "$clone_mock_resp")
  TOTAL_CLONED_MOCKS=$((TOTAL_CLONED_MOCKS + 1))
  echo "   Cloned mock $source_mock  ->  $cloned_mock_id"
else
  source_mock="${ALL_MOCK_IDS[0]}"
  clone_mock_resp=$(curl -s -X POST "$BASE_URL/api/mocks/$source_mock/clone" \
    -H "Content-Type: application/json" \
    -d "{\"name\": \"Cloned Mock\"}")
  cloned_mock_id=$(extract_id "$clone_mock_resp")
  TOTAL_CLONED_MOCKS=$((TOTAL_CLONED_MOCKS + 1))
  echo "   Cloned mock $source_mock  ->  $cloned_mock_id"
fi
echo ""

# =============================================================================
#  PHASE 8 — Move cloned mock to last project  (PUT /api/mocks/{id}/move)
# =============================================================================
echo ">> PHASE 8: Moving cloned mock to last project"
echo "   -----------------------------------------"

target_project="${PROJECT_IDS[$((N - 1))]}"
curl -s -X PUT "$BASE_URL/api/mocks/$cloned_mock_id/move" \
  -H "Content-Type: application/json" \
  -d "{\"targetProjectId\": \"$target_project\"}" > /dev/null
TOTAL_MOVED=$((TOTAL_MOVED + 1))
echo "   Moved mock $cloned_mock_id  ->  project $target_project"
echo ""

# =============================================================================
#  PHASE 9 — Delete the cloned mock  (DELETE /api/mocks/{id})
# =============================================================================
echo ">> PHASE 9: Deleting the moved mock"
echo "   -----------------------------------------"

curl -s -X DELETE "$BASE_URL/api/mocks/$cloned_mock_id" > /dev/null
TOTAL_DELETED_MOCKS=$((TOTAL_DELETED_MOCKS + 1))
echo "   Deleted mock $cloned_mock_id"
echo ""

# =============================================================================
#  PHASE 10 — Delete the cloned project  (DELETE /api/projects/{id})
# =============================================================================
echo ">> PHASE 10: Deleting the cloned project"
echo "   -----------------------------------------"

curl -s -X DELETE "$BASE_URL/api/projects/$cloned_project_id" > /dev/null
TOTAL_DELETED_PROJECTS=$((TOTAL_DELETED_PROJECTS + 1))
echo "   Deleted project $cloned_project_id"
echo ""

# =============================================================================
#  Summary
# =============================================================================
echo "============================================="
echo "  Data Loading Complete!"
echo "============================================="
echo ""
echo "  Configuration"
echo "    N = $N"
echo "    Hits per mock = $HITS_PER_MOCK"
echo ""
echo "  Actions performed:"
echo "    Projects created ............. $TOTAL_PROJECTS"
echo "    Mocks created ................ $TOTAL_MOCKS"
echo "    Projects cloned .............. $TOTAL_CLONED_PROJECTS"
echo "    Mocks cloned ................. $TOTAL_CLONED_MOCKS"
echo "    Mocks updated ................ $TOTAL_UPDATED"
echo "    Projects updated ............. $TOTAL_PROJECT_UPDATED"
echo "    Mocks moved .................. $TOTAL_MOVED"
echo "    Mocks re-published ........... $TOTAL_PUBLISHED"
echo "    WireMock hits (traffic) ...... $TOTAL_HITS"
echo "    Mocks deleted ................ $TOTAL_DELETED_MOCKS"
echo "    Projects deleted ............. $TOTAL_DELETED_PROJECTS"
echo ""
echo "  Remaining data:"
echo "    Projects ..................... $TOTAL_PROJECTS"
echo "    Mocks ........................ $TOTAL_MOCKS"
echo ""
echo "  API endpoints exercised:"
echo "    POST   /api/projects                         (create project)"
echo "    GET    /api/projects                         (list projects)"
echo "    GET    /api/projects/{id}                    (get project + embedded mocks)"
echo "    PUT    /api/projects/{id}                    (update project)"
echo "    POST   /api/projects/{id}/clone              (clone project)"
echo "    DELETE /api/projects/{id}                    (delete project)"
echo "    POST   /api/mocks                            (create mock — auto-publishes)"
echo "    GET    /api/mocks/{id}                       (get mock)"
echo "    PUT    /api/mocks/{id}                       (update mock)"
echo "    POST   /api/mocks/{id}/clone                 (clone mock)"
echo "    PUT    /api/mocks/{id}/move                  (move mock)"
echo "    POST   /api/mocks/{id}/republish             (re-publish to WireMock)"
echo "    DELETE /api/mocks/{id}                       (delete mock)"
echo "    GET    /api/backoffice/notifications         (get notifications, paginated)"
echo ""
echo "  13/13 endpoints covered"
echo "============================================="
