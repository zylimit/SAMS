import axios from "axios";

const TOKEN_KEY = "sams.token";

export function getToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
export function setToken(token: string) {
  try { localStorage.setItem(TOKEN_KEY, token); } catch { /* ignore */ }
}
export function clearToken() {
  try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
}

let unauthorizedHandler: (() => void) | null = null;
export function setUnauthorizedHandler(fn: (() => void) | null) {
  unauthorizedHandler = fn;
}

export const http = axios.create();
http.interceptors.request.use((cfg) => {
  const t = getToken();
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});
http.interceptors.response.use(
  (r) => r,
  (err) => {
    const url = String(err.config?.url ?? "");
    const skip = url.includes("/api/auth/login") || url.includes("/api/auth/me") || url.includes("/api/auth/change-password");
    if (err.response?.status === 401 && !skip) {
      clearToken();
      unauthorizedHandler?.();
    }
    return Promise.reject(err);
  },
);

export function errDetail(err: unknown): string {
  const e = err as { response?: { data?: { detail?: unknown }; status?: number }; message?: string };
  const d = e.response?.data?.detail;
  if (typeof d === "string") return d;
  if (d != null) return JSON.stringify(d);
  if (e.response?.status) return `HTTP ${e.response.status}`;
  return e.message ?? String(err);
}

export async function downloadBlob(res: { data: Blob; headers: Record<string, string> }, fallback: string) {
  const disp = res.headers["x-filename"] || res.headers["content-disposition"] || "";
  const m = String(disp).match(/filename="?([^"]+)"?/);
  const filename = m ? m[1] : fallback;
  const url = URL.createObjectURL(res.data);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
