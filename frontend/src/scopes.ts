import type { Feature, FeatureCollection } from "./api";

const FULL = "*";
const CATEGORY_DB_TO_NODE: Record<string, string> = {
  "存量": "EXISTING",
  "规划": "PLANNED",
  "勘测": "SURVEY",
};

export interface ScopeCtx {
  isAdmin: boolean;
  scopes: string[];
}

const fullAccess = (ctx: ScopeCtx): boolean => ctx.isAdmin || ctx.scopes.includes(FULL);

export function siteRootVisible(ctx: ScopeCtx): boolean {
  return fullAccess(ctx) || ctx.scopes.some(s => s === "site" || s.startsWith("site:"));
}

export function operatorVisible(ctx: ScopeCtx, op: string): boolean {
  if (fullAccess(ctx) || ctx.scopes.includes("site")) return true;
  return ctx.scopes.some(s => s === `site:${op}` || s.startsWith(`site:${op}:`));
}

export function categoryVisible(ctx: ScopeCtx, op: string, catZh: string): boolean {
  if (fullAccess(ctx) || ctx.scopes.includes("site") || ctx.scopes.includes(`site:${op}`)) return true;
  const node = CATEGORY_DB_TO_NODE[catZh];
  return node != null && ctx.scopes.includes(`site:${op}:${node}`);
}

export function roadVisible(ctx: ScopeCtx): boolean {
  return fullAccess(ctx) || ctx.scopes.includes("road");
}

export function lessorVisible(ctx: ScopeCtx): boolean {
  return fullAccess(ctx) || ctx.scopes.includes("lessor");
}

export function areaVisible(ctx: ScopeCtx, op: string): boolean {
  if (fullAccess(ctx) || ctx.scopes.includes("site") || ctx.scopes.includes(`site:${op}`)) return true;
  return ctx.scopes.includes(`site:${op}:AREA`);
}

export function siteFeatureVisible(ctx: ScopeCtx, f: Feature): boolean {
  if (fullAccess(ctx) || ctx.scopes.includes("site")) return true;
  const p = f.properties ?? {};
  const op = String(p.operator ?? "");
  if (!op) return false;
  if (ctx.scopes.includes(`site:${op}`)) return true;
  const node = CATEGORY_DB_TO_NODE[String(p.category ?? "")];
  return node != null && ctx.scopes.includes(`site:${op}:${node}`);
}

export function filterByScope(ctx: ScopeCtx, kind: "site" | "road" | "lessor" | "area", fc: FeatureCollection): FeatureCollection {
  if (fullAccess(ctx)) return fc;
  if (kind === "road") return roadVisible(ctx) ? fc : { ...fc, features: [] };
  if (kind === "lessor") return lessorVisible(ctx) ? fc : { ...fc, features: [] };
  if (kind === "area") {
    return { ...fc, features: fc.features.filter(f => areaVisible(ctx, String(f.properties?.operator ?? ""))) };
  }
  return { ...fc, features: fc.features.filter(f => siteFeatureVisible(ctx, f)) };
}

export function scopeCtx(user: { is_admin?: boolean; scopes?: string[] } | null): ScopeCtx {
  return { isAdmin: !!user?.is_admin, scopes: user?.scopes ?? [] };
}
