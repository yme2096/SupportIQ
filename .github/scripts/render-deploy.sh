#!/usr/bin/env bash
set -euo pipefail

service_id="${1:?Render service ID is required}"
api_key="${RENDER_API_KEY:?Render API key is required in the environment}"
commit_id="${2:?Commit SHA is required}"
api="https://api.render.com/v1/services/${service_id}/deploys"

response="$(curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer ${api_key}" \
  --header 'Accept: application/json' \
  --header 'Content-Type: application/json' \
  --data "{\"commitId\":\"${commit_id}\",\"clearCache\":\"do_not_clear\"}" \
  "$api")"
deploy_id="$(printf '%s' "$response" | jq -r '.id // .deploy.id // empty')"
if [[ -z "$deploy_id" ]]; then
  echo 'Render accepted no deployment ID; inspect service Deploys in the dashboard.' >&2
  exit 1
fi

for _ in $(seq 1 90); do
  status="$(curl --fail --silent --show-error \
    --header "Authorization: Bearer ${api_key}" \
    --header 'Accept: application/json' \
    "${api}/${deploy_id}" | jq -r '.status')"
  case "$status" in
    live) exit 0 ;;
    build_failed|update_failed|pre_deploy_failed|canceled|deactivated)
      echo "Render deploy failed with status: $status" >&2
      exit 1
      ;;
    created|queued|build_in_progress|update_in_progress|pre_deploy_in_progress|"") sleep 10 ;;
    *) echo "Unknown Render deploy status: $status" >&2; exit 1 ;;
  esac
done

echo 'Timed out while waiting for Render deployment. Check the Render dashboard and logs.' >&2
exit 1
