import { downloadBlob, errDetail, http, setToken, clearToken, getToken } from "./client";
export { getToken, setToken, clearToken, setUnauthorizedHandler } from "./client";

export interface Feature {
  type: "Feature";
  id?: string | number;
  geometry: { type: string; coordinates: unknown } | null;
  properties: Record<string, unknown> | null;
}
export type FeatureCollection = { type: "FeatureCollection"; features: Feature[] };
export interface AuthUser {
  username: string;
  is_admin: boolean;
  perms: Record<string, boolean>;
  scopes: string[];
  must_change_password: boolean;
}

export async function login(username: string, password: string) {
  try {
    const { data } = await http.post("/api/auth/login", { username, password });
    setToken(data.token);
    return data as { token: string; user: AuthUser };
  } catch (e) { throw new Error(errDetail(e)); }
}
export async function logout() {
  try { await http.post("/api/auth/logout"); } catch { /* ignore */ }
  clearToken();
}
export async function fetchMe(): Promise<AuthUser> {
  try {
    const { data } = await http.get("/api/auth/me");
    return data.user;
  } catch (e) { throw new Error(errDetail(e)); }
}
export async function changePassword(oldPassword: string, newPassword: string) {
  try {
    await http.post("/api/auth/change-password", { old_password: oldPassword, new_password: newPassword });
  } catch (e) { throw new Error(errDetail(e)); }
}

export type CleaningAction = "auto_fix" | "keep" | "discard";
export type Decision = "overwrite" | "ignore";
export interface LayerStamp {
  operator?: string | null;
  category?: string | null;
  type?: string | null;
  target_kind: "site" | "road" | "lessor" | "area";
}

export async function uploadFile(file: File, stamp?: LayerStamp) {
  const fd = new FormData();
  fd.append("file", file);
  if (stamp) {
    if (stamp.operator) fd.append("operator", stamp.operator);
    if (stamp.category) fd.append("category", stamp.category);
    if (stamp.type) fd.append("type", stamp.type);
    fd.append("target_kind", stamp.target_kind);
  }
  try {
    const { data } = await http.post("/api/import", fd);
    return data;
  } catch (e) {
    const err = e as { response?: { status?: number } };
    if (err.response?.status === 413) throw new Error("服务端拒绝：文件超过 100MB 上限");
    throw new Error(errDetail(e));
  }
}
export async function proceedToConflicts(sessionId: string, decisions: { row_id: string; action: CleaningAction }[]) {
  const { data } = await http.post(`/api/import/${sessionId}/proceed-to-conflicts`, { decisions });
  return data;
}
export async function backToCleaning(sessionId: string) {
  const { data } = await http.post(`/api/import/${sessionId}/back-to-cleaning`);
  return data;
}
export async function commitImport(sessionId: string, decisions: { key: string; action: Decision }[]) {
  const { data } = await http.post(`/api/import/${sessionId}/commit`, { decisions });
  return data;
}
export async function cancelImport(sessionId: string) {
  await http.delete(`/api/import/${sessionId}`);
}
export async function fetchImportProgress(sessionId: string) {
  const { data } = await http.get(`/api/import/${sessionId}/progress`);
  return data as { done: number; total: number; pct: number; phase: string };
}
export async function downloadConflictsXlsx(sessionId: string) {
  const res = await http.get(`/api/import/${sessionId}/conflicts.xlsx`, { responseType: "blob" });
  await downloadBlob(res, "conflicts.xlsx");
}

export async function fetchAll() {
  const [sites, roads, lessors, areas] = await Promise.all([
    http.get("/api/sites"), http.get("/api/roads"), http.get("/api/lessors"), http.get("/api/areas"),
  ]);
  return { sites: sites.data, roads: roads.data, lessors: lessors.data, areas: areas.data };
}
export async function fetchBaselineState() {
  const { data } = await http.get("/api/baseline-state");
  return data;
}
export async function clearBaseline() {
  const { data } = await http.delete("/api/baseline");
  return data;
}
export async function health() {
  const { data } = await http.get("/health");
  return data as { status: string; db: boolean };
}

export async function listRestorePoints() {
  const { data } = await http.get("/api/restore-points");
  return data;
}
export async function createRestorePoint(note?: string) {
  const { data } = await http.post("/api/restore-points", { note: note ?? null });
  return data;
}
export async function rollbackToPoint(id: number) {
  await http.post(`/api/restore-points/${id}/rollback`);
}
export async function deleteRestorePoint(id: number) {
  await http.delete(`/api/restore-points/${id}`);
}
export async function listBackups() {
  const { data } = await http.get("/api/backups");
  return data;
}
export async function restoreBackup(id: number) {
  await http.post(`/api/backups/${id}/restore`);
}
export async function listAuditLog(filters: Record<string, string>, page = 1, pageSize = 50) {
  const { data } = await http.get("/api/audit-log", { params: { ...filters, page, page_size: pageSize } });
  return data;
}
export async function exportAuditLog(filters: Record<string, string>) {
  const res = await http.get("/api/audit-log/export", { params: filters, responseType: "blob" });
  await downloadBlob(res, "audit_log.xlsx");
}

export async function exportAll(npRadiusM?: number) {
  const res = await http.get("/api/export/all", { params: npRadiusM != null ? { np_radius_m: npRadiusM } : {}, responseType: "blob" });
  await downloadBlob(res, "export_full.kmz");
}
export async function exportSelection(polygon: object, npRadiusM?: number, mode?: string) {
  const res = await http.post("/api/export/selection", { polygon, np_radius_m: npRadiusM, mode }, { responseType: "blob" });
  await downloadBlob(res, "export_region.kmz");
}
export async function exportSelectionIds(keys: { site_id: string; option: string }[], npRadiusM?: number) {
  const res = await http.post("/api/export/selection_ids", { keys, np_radius_m: npRadiusM }, { responseType: "blob" });
  await downloadBlob(res, "export_region.kmz");
}

export async function updateSite(key: { site_id: string; option: string }, patch: Record<string, unknown>) {
  const { data } = await http.patch("/api/sites", { site_id: key.site_id, option: key.option, patch });
  return data as Feature;
}
export async function deleteSites(keys: { site_id: string; option: string }[]) {
  const { data } = await http.post("/api/sites/delete", { keys });
  return data as { deleted: number; undo_id: number };
}
export async function fetchDeleteHistory() {
  const { data } = await http.get("/api/sites/delete-history");
  return data;
}
export async function undoDelete(undoId: number) {
  const { data } = await http.post(`/api/sites/undo-delete/${undoId}`);
  return data as { restored: number; requested: number };
}

export async function listAdminUsers() { const { data } = await http.get("/api/admin/users"); return data; }
export async function createAdminUser(username: string, roleId: number, password: string) {
  const { data } = await http.post("/api/admin/users", { username, role_id: roleId, password });
  return data;
}
export async function resetUserPassword(userId: number, password: string) {
  await http.post(`/api/admin/users/${userId}/reset-password`, { password });
}
export async function toggleUserDisabled(userId: number) {
  const { data } = await http.post(`/api/admin/users/${userId}/toggle-disabled`);
  return data;
}
export async function listAdminRoles() { const { data } = await http.get("/api/admin/roles"); return data; }
export async function createAdminRole(name: string, perms: Record<string, boolean>, scopes: string[]) {
  const { data } = await http.post("/api/admin/roles", { name, perms, scopes });
  return data;
}
export async function updateAdminRole(roleId: number, patch: object) {
  await http.patch(`/api/admin/roles/${roleId}`, patch);
}
export async function deleteAdminRole(roleId: number) {
  await http.delete(`/api/admin/roles/${roleId}`);
}
