#!/usr/bin/env bash
set -euo pipefail
# Revalidate a complete draft and ORIGINAL unsigned GitHub build before making it public.
python3 scripts/release/validate_publication.py
release_id=$(cat validated-draft-id)
[[ "$release_id" =~ ^[0-9]+$ ]]
# This transition is reachable only through a separately approved manual main run.
gh api --method PATCH "repos/$GH_REPO/releases/$release_id" -F draft=false > "$RUNNER_TEMP/published-release.json"
