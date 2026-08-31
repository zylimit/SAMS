#!/usr/bin/env bash
# SAMS API parity smoke: login → import → commit → CRUD → export → restore → RBAC
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
KML="${KML:-$(dirname "$0")/../data/sample_sites.kml}"

json() { python3 -c "$1"; }

echo "== health =="
curl -sf "$BASE/health" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('status')=='ok' and d.get('db') is True, d"

login() {
  local pw="$1"
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"${pw}\"}"
}

echo "== login admin =="
LOGIN=$(login admin123)
if ! echo "$LOGIN" | python3 -c "import sys,json; json.load(sys.stdin)['token']" >/dev/null 2>&1; then
  LOGIN=$(login admin1234)
fi
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "== change password if required =="
NEED=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['must_change_password'])")
if [ "$NEED" = "True" ] || [ "$NEED" = "true" ]; then
  curl -sf -X POST "$BASE/api/auth/change-password" "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d '{"old_password":"admin123","new_password":"admin1234"}' >/dev/null
  LOGIN=$(login admin1234)
  TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
  AUTH=(-H "Authorization: Bearer $TOKEN")
fi

echo "== me =="
curl -sf "$BASE/api/auth/me" "${AUTH[@]}" | python3 -c "import sys,json; u=json.load(sys.stdin)['user']; assert u['is_admin'] and not u['must_change_password']"

echo "== import sample KML =="
IMP=$(curl -sf -X POST "$BASE/api/import" "${AUTH[@]}" \
  -F "file=@${KML}" -F "target_kind=site" -F "operator=Globe" -F "category=存量" -F "type=Macro")
SID=$(echo "$IMP" | python3 -c "import sys,json; print(json.load(sys.stdin)['session_id'])")
echo "session $SID"

echo "== proceed-to-conflicts =="
curl -sf -X POST "$BASE/api/import/${SID}/proceed-to-conflicts" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"decisions":[]}' >/dev/null

echo "== commit =="
COMMIT=$(curl -sf -X POST "$BASE/api/import/${SID}/commit" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"decisions":[]}')
echo "$COMMIT" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d['stats']['site']['inserted']>=1, d"

echo "== list sites =="
curl -sf "$BASE/api/sites" "${AUTH[@]}" | python3 -c "
import sys,json
fc=json.load(sys.stdin)
assert fc['type']=='FeatureCollection'
ids=[f['properties']['site_id'] for f in fc['features']]
assert 'E2E001' in ids, ids
st=[f['properties']['site_status'] for f in fc['features'] if f['properties']['site_id']=='E2E001'][0]
assert st=='undetermined', st
"

echo "== patch site =="
curl -sf -X PATCH "$BASE/api/sites" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"site_id":"E2E001","option":"A","patch":{"project":"SAMS-E2E-EDIT"}}' >/dev/null

echo "== delete + undo =="
DEL=$(curl -sf -X POST "$BASE/api/sites/delete" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"keys":[{"site_id":"E2E001","option":"A"}]}')
UNDO=$(echo "$DEL" | python3 -c "import sys,json; print(json.load(sys.stdin)['undo_id'])")
curl -sf -X POST "$BASE/api/sites/undo-delete/${UNDO}" "${AUTH[@]}" >/dev/null

echo "== export all =="
curl -sf "$BASE/api/export/all" "${AUTH[@]}" -o /tmp/sams_export.kmz
python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/sams_export.kmz'); assert 'doc.kml' in z.namelist(); k=z.read('doc.kml').decode(); assert 'E2E001' in k and 'E2E002' in k"

echo "== restore point =="
RP=$(curl -sf -X POST "$BASE/api/restore-points" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"note":"e2e"}')
RPID=$(echo "$RP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
curl -sf -X POST "$BASE/api/sites/delete" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"keys":[{"site_id":"E2E002","option":""}]}' >/dev/null
curl -sf -X POST "$BASE/api/restore-points/${RPID}/rollback" "${AUTH[@]}" >/dev/null
curl -sf "$BASE/api/sites" "${AUTH[@]}" | python3 -c "
import sys,json
ids=[f['properties']['site_id'] for f in json.load(sys.stdin)['features']]
assert 'E2E002' in ids, ids
"

echo "== admin role + scoped user =="
ROLE=$(curl -sf -X POST "$BASE/api/admin/roles" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"name":"e2e-viewer","perms":{"import":false,"export":true,"edit_delete":false,"danger":false},"scopes":["site:Globe:EXISTING"]}')
RID=$(echo "$ROLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
curl -sf -X POST "$BASE/api/admin/users" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"username\":\"e2euser\",\"role_id\":${RID},\"password\":\"e2euser12\"}" >/dev/null
VLOGIN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"e2euser","password":"e2euser12"}')
VTOKEN=$(echo "$VLOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
VAUTH=(-H "Authorization: Bearer $VTOKEN")
code=$(curl -s -o /tmp/sams_imp.json -w '%{http_code}' -X POST "$BASE/api/import" "${VAUTH[@]}" \
  -F "file=@${KML}" -F "target_kind=site" -F "operator=Globe" -F "category=存量" -F "type=Macro")
test "$code" = "403"
code=$(curl -s -o /tmp/sams_clear.json -w '%{http_code}' -X DELETE "$BASE/api/baseline" "${VAUTH[@]}")
test "$code" = "403"

echo "== 401 without token =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/sites")
test "$code" = "401"

echo "E2E OK"
