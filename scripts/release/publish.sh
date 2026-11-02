#!/usr/bin/env bash
set -euo pipefail
python3 scripts/release/verify.py bundle --plan scripts/release/plan.json --directory delivery --tools "$ANDROID_HOME/build-tools/35.0.0"
readarray -t plan < <(python3 -c 'import json; p=json.load(open("scripts/release/plan.json")); print(p["tag"]); print(p["source_commit"])')
tag=${plan[0]}
source_commit=${plan[1]}
# Include drafts as well as published releases; a by-tag endpoint alone can miss drafts.
gh api --paginate --slurp "repos/$GH_REPO/releases?per_page=100" > "$RUNNER_TEMP/releases.json"
python3 - "$RUNNER_TEMP/releases.json" "$tag" <<'PY'
import json, sys
pages=json.load(open(sys.argv[1]))
assert all(r['tag_name'] != sys.argv[2] for page in pages for r in page), 'Release/draft already exists'
PY
# Only a confirmed HTTP 404 means absent. Authentication/network failures stop publication.
for endpoint in "git/ref/tags/$tag"; do
  if gh api "repos/$GH_REPO/$endpoint" > "$RUNNER_TEMP/probe.json" 2> "$RUNNER_TEMP/probe.err"; then
    echo '::error::Existing tag/release: refusing replacement'; exit 1
  fi
  python3 - "$RUNNER_TEMP/probe.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1])).get('status') == '404', 'Cannot establish tag/release absence'
PY
done
assets=(delivery/*)
gh release create "$tag" --target "$source_commit" --draft --prerelease --title "FortGPS Field ${tag#v}" --notes-file delivery/RELEASE_NOTES.md "${assets[@]}"
mkdir draft-readback
gh release download "$tag" --dir draft-readback
python3 scripts/release/verify.py bundle --plan scripts/release/plan.json --directory draft-readback --tools "$ANDROID_HOME/build-tools/35.0.0"
for asset in delivery/*; do cmp "$asset" "draft-readback/${asset##*/}"; done
gh release edit "$tag" --draft=false
# A later public-readback failure leaves this release for investigation; never overwrite automatically.
