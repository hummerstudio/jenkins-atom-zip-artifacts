#!/usr/bin/env bash
# Round trip plus the two guards: writing outside the workspace is refused, and so is a zip-slip entry.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. zip then unzip the same tree
dir="$(mktemp -d)"; mkdir -p "$dir/dist/nested"
printf 'jar-bytes\n' >"$dir/dist/app.jar"
printf 'readme\n' >"$dir/dist/nested/README.md"
printf '{"operation": "zip", "archive": "out/app.zip", "includes": "dist/**", "excludes": "**/*.tmp"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "zip failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" files)" = "2" ] || fail "file count: $(cat "$dir/output.json")"
[ "$(output "$dir" archive)" = "$dir/out/app.zip" ] || fail "archive path: $(cat "$dir/output.json")"
[ -f "$dir/out/app.zip" ] || fail "no archive written"
[ -f "$dir/out/app.zip.partial" ] && fail "the .partial file must be gone after a successful pack"
grep -q '"type":"artifact"' "$dir/output.json" || fail "the archive must be published as an artifact"

printf '{"operation": "unzip", "archive": "out/app.zip", "target": "unpacked"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "unzip failed: $(cat "$dir/output.json")"
[ "$(output "$dir" entries)" = "2" ] || fail "entries: $(cat "$dir/output.json")"
cmp -s "$dir/dist/app.jar" "$dir/unpacked/dist/app.jar" || fail "round trip changed the bytes"

# 2. writing the archive outside the workspace is refused
dir="$(mktemp -d)"; printf 'x\n' >"$dir/a.txt"
printf '{"operation": "zip", "archive": "../escape.zip", "includes": "a.txt"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "an archive outside the workspace must be refused"
grep -q "inside the workspace" "$dir/output.json" || fail "message not readable"

# 3. a zip-slip entry is refused before anything is written
dir="$(mktemp -d)"
python3 - "$dir" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1] + "/evil.zip", "w") as z:
    z.writestr("ok.txt", "fine")
    z.writestr("../escaped.txt", "should never be written")
PY
printf '{"operation": "unzip", "archive": "evil.zip", "target": "out"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a zip-slip entry must be refused"
grep -q "outside the target directory" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
[ -f "$dir/../escaped.txt" ] && fail "the entry escaped into the parent directory"

# 4. a missing archive is a clear error
dir="$(mktemp -d)"; printf '{"operation": "unzip", "archive": "nope.zip"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing archive must fail"
grep -q "no archive at" "$dir/output.json" || fail "message not readable"

echo "smoke ok: zip/unzip round trip, no .partial left, workspace guard, zip-slip guard"
