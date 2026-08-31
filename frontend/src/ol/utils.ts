export function formatCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "0";
  return n.toLocaleString("en-US");
}

export function nameOf(f: { properties?: Record<string, unknown> | null }): string {
  const p = f.properties;
  if (!p) return "(未命名)";
  if (p.kind === "site") return `${p.site_id ?? "?"}${p.option ? ` / ${p.option}` : ""}`;
  if (p.kind === "road") return (p.property as string) || `road #${p.id}`;
  if (p.kind === "lessor") return (p.lessor_name as string) || `lessor ${p.fid}`;
  if (p.kind === "area") return (p.name as string) || `area #${p.id}`;
  return "(未知)";
}

export const STATUS_COLOR: Record<string, string> = {
  positive: "#4caf50",
  negative: "#f44336",
  undetermined: "#ffb300",
  "": "#9e9e9e",
  Unfriendly: "#f44336",
  Normal: "#ffb300",
  road: "#795548",
};

export function statusBucket(raw: unknown): string {
  const s = String(raw ?? "").toLowerCase();
  return s === "positive" || s === "negative" || s === "undetermined" ? s : "other";
}

export const LAYER_COLOR: Record<string, string> = { "存量": "#f97316", "规划": "#a855f7" };
export const OPERATOR_COLOR: Record<string, string> = { Globe: "#3b82f6", Smart: "#22c55e", Dito: "#ef4444" };
export const OPERATOR_LETTER: Record<string, string> = { Globe: "G", Smart: "S", Dito: "D" };

export function operatorColor(op: string | null | undefined): string {
  return OPERATOR_COLOR[op ?? ""] ?? STATUS_COLOR[""];
}

/** hex + alpha 0–1 → rgba()，避免 `#3b82f659` 这种非法 CSS。 */
export function hexAlpha(hex: string, alpha: number): string {
  const h = hex.replace("#", "");
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${alpha})`;
}
export function siteStatusColor(status: string | null | undefined): string {
  const s = (status ?? "").toLowerCase();
  if (s === "positive") return STATUS_COLOR.positive;
  if (s === "negative") return STATUS_COLOR.negative;
  if (s === "undetermined") return STATUS_COLOR.undetermined;
  return STATUS_COLOR[""];
}
export function lessorLineColor(rel: string | null | undefined): string {
  return (rel ?? "").toLowerCase() === "normal" ? STATUS_COLOR.Normal : STATUS_COLOR.Unfriendly;
}

export type ShapeKind = "triangle" | "circle" | "square" | "diamond";
export interface SiteShape { shape: ShapeKind; filled: boolean; ring?: boolean; }
export const TYPE_SHAPE: Record<string, SiteShape> = {
  Macro: { shape: "triangle", filled: true },
  Micro: { shape: "circle", filled: true },
  IBS: { shape: "square", filled: true },
  "Macro NP": { shape: "triangle", filled: false, ring: true },
  "Micro NP": { shape: "circle", filled: false, ring: true },
  "Macro-ongoing": { shape: "diamond", filled: true },
  "Micro-ongoing": { shape: "diamond", filled: false },
};
export const DEFAULT_SITE_SHAPE: SiteShape = { shape: "circle", filled: true };
export function siteShape(type: string | null | undefined): SiteShape {
  return type == null ? DEFAULT_SITE_SHAPE : (TYPE_SHAPE[type] ?? DEFAULT_SITE_SHAPE);
}

export const DEFAULT_NP_RADIUS_M = 200;
export const NP_RADIUS_OPTIONS = [50, 100, 150, 200, 250] as const;
export const NP_RADIUS_KEY = "sams.np_radius_m";
export function readNpRadius(): number {
  try {
    const raw = Number(localStorage.getItem(NP_RADIUS_KEY));
    if ((NP_RADIUS_OPTIONS as readonly number[]).includes(raw)) return raw;
  } catch { /* ignore */ }
  return DEFAULT_NP_RADIUS_M;
}

export const POLYGON_LABELS_KEY = "sams.polygonLabels";
export function readPolygonLabels(): boolean {
  try { const v = localStorage.getItem(POLYGON_LABELS_KEY); return v === null ? true : v === "1"; }
  catch { return true; }
}
export const OPERATOR_LETTERS_KEY = "sams.operatorLetters";
export function readOperatorLetters(): boolean {
  try { const v = localStorage.getItem(OPERATOR_LETTERS_KEY); return v === null ? true : v === "1"; }
  catch { return true; }
}

export function metersToMapUnits(m: number, lat: number): number {
  return m / Math.cos((lat * Math.PI) / 180);
}

export const OPERATORS = ["Globe", "Smart", "Dito"] as const;
export const CATS = [
  { id: "存量", node: "EXISTING", types: ["Macro", "Micro", "IBS"] },
  { id: "规划", node: "PLANNED", types: ["Macro NP", "Micro NP"] },
  { id: "勘测", node: "SURVEY", types: ["Macro-ongoing", "Micro-ongoing"] },
] as const;
