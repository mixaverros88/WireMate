#!/usr/bin/env bash
# generate-postman.sh
# Triggered by Claude Code after any file write.
# If the changed file is a *Controller.java, it regenerates the Postman collection.

set -euo pipefail

FILE_PATH="${1:-}"

# Only proceed for Controller files
if [[ "$FILE_PATH" != *Controller.java ]]; then
  exit 0
fi

echo "[postman-hook] Controller change detected: $FILE_PATH"

# Derive collection name from the controller file name
# e.g. ProjectController.java -> ProjectController
CONTROLLER_NAME=$(basename "$FILE_PATH" .java)
COLLECTION_FILE="environment/postman/${CONTROLLER_NAME}.postman_collection.json"

mkdir -p environment/postman

# Read the controller source
CONTROLLER_SOURCE=$(cat "$FILE_PATH")

# Use Claude to parse the controller and produce a Postman collection JSON
RESPONSE=$(curl -s https://api.anthropic.com/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d "$(jq -n \
    --arg source "$CONTROLLER_SOURCE" \
    --arg name "$CONTROLLER_NAME" \
    '{
      model: "claude-sonnet-4-20250514",
      max_tokens: 4096,
      messages: [
        {
          role: "user",
          content: ("You are a tool that generates Postman collections from Spring Boot controllers.\n\nGiven the following Spring Boot controller, produce a valid Postman Collection v2.1 JSON.\n\nRules:\n- Use {{baseUrl}} as the base URL variable\n- Group all requests under a folder named after the controller\n- Infer path variables and query params from the method signatures\n- Use appropriate HTTP methods from the mapping annotations\n- For POST/PUT methods include an example JSON body based on the request DTO fields if visible\n- Return ONLY raw JSON, no markdown, no explanation\n\nController name: " + $name + "\n\nController source:\n" + $source)
        }
      ]
    }'
  )")

# Extract the text content from the Claude response
COLLECTION_JSON=$(echo "$RESPONSE" | jq -r '.content[0].text')

# Validate it looks like JSON before writing
if echo "$COLLECTION_JSON" | jq empty 2>/dev/null; then
  echo "$COLLECTION_JSON" > "$COLLECTION_FILE"
  echo "[postman-hook] Collection written to $COLLECTION_FILE"
else
  echo "[postman-hook] ERROR: Claude response was not valid JSON. Skipping write."
  echo "$COLLECTION_JSON"
  exit 1
fi
