import { defineStore } from "pinia";
import { markRaw, ref, shallowRef } from "vue";
import * as api from "../api";
import { t } from "../i18n";
import { nameOf, statusBucket } from "../ol/utils";
import type { AuthUser, Feature, FeatureCollection, LayerStamp } from "../api";

const EMPTY: FeatureCollection = { type: "FeatureCollection", features: [] };
const EMPTY_LAYER_IDS = markRaw(new Map<string, string[]>());
const EMPTY_LAYER_FEATS = markRaw(new Map<string, Feature[]>());
const MAX_FILE = 100 * 1024 * 1024;
const SEARCH_CAP = 200;

function rawFc(fc: FeatureCollection | undefined | null): FeatureCollection {
  if (!fc || !Array.isArray(fc.features)) return EMPTY;
  return markRaw(fc);
}

function indexFeatures(...cols: FeatureCollection[]): Map<string, Feature> {
  const m = new Map<string, Feature>();
  for (const col of cols) {
    for (const f of col.features) {
      if (f.id != null) m.set(String(f.id), f);
    }
  }
  return markRaw(m);
}

/** 树节点 / 眼睛列表共用：一次分组，避免每次开窗再扫 2 万+ 条。 */
function buildLayerMaps(sites: FeatureCollection, roads: FeatureCollection, lessors: FeatureCollection, areas: FeatureCollection) {
  const ids = new Map<string, string[]>();
  const feats = new Map<string, Feature[]>();
  const push = (key: string, f: Feature, id: string) => {
    let ia = ids.get(key);
    if (!ia) {
      ia = [];
      ids.set(key, ia);
      feats.set(key, []);
    }
    ia.push(id);
    feats.get(key)!.push(f);
  };
  for (const f of sites.features) {
    const id = String(f.id);
    const p = f.properties ?? {};
    const op = String(p.operator ?? "");
    const cat = String(p.category ?? "");
    const tp = String(p.type ?? "");
    const st = statusBucket(p.site_status);
    push("site", f, id);
    push(op, f, id);
    push(`${op}/${cat}`, f, id);
    push(`${op}/${cat}/${tp}`, f, id);
    push(`${op}/${cat}/${tp}/${st}`, f, id);
  }
  for (const f of roads.features) push("road", f, String(f.id));
  for (const f of lessors.features) {
    const id = String(f.id);
    const rel = String((f.properties ?? {}).relationship ?? "");
    push("lessor", f, id);
    push(`lessor/${rel}`, f, id);
  }
  for (const f of areas.features) {
    const id = String(f.id);
    const op = String((f.properties ?? {}).operator ?? "");
    push(`area/${op}`, f, id);
  }
  return { ids: markRaw(ids), feats: markRaw(feats) };
}

export const useAppStore = defineStore("app", () => {
  const currentUser = ref<AuthUser | null>(null);
  const authChecking = ref(true);
  const sites = shallowRef<FeatureCollection>(EMPTY);
  const roads = shallowRef<FeatureCollection>(EMPTY);
  const lessors = shallowRef<FeatureCollection>(EMPTY);
  const areas = shallowRef<FeatureCollection>(EMPTY);
  const featureById = shallowRef<Map<string, Feature>>(new Map());
  const layerIds = shallowRef<Map<string, string[]>>(EMPTY_LAYER_IDS);
  const layerFeats = shallowRef<Map<string, Feature[]>>(EMPTY_LAYER_FEATS);
  const selected = ref<Feature | null>(null);
  const logs = ref<{ ts: string; level: "info" | "warn" | "error"; msg: string }[]>([]);
  const phase = ref("idle");
  const dbOk = ref<boolean | null>(null);
  const baseline = ref<Record<string, unknown> | null>(null);
  const importSession = ref<string | null>(null);
  const cleanings = ref<unknown[]>([]);
  const conflicts = ref<unknown[]>([]);
  const cleaningDecisions = ref<Record<string, string>>({});
  const conflictDecisions = ref<Record<string, string>>({});
  const baselineRegion = ref<unknown>(null);
  const importFile = ref("");
  const progress = ref({ done: 0, total: 0, pct: 0 });
  const searchResults = ref<{ query: string; total: number; results: Feature[] } | null>(null);
  const hidden = ref<Record<string, boolean>>({});
  const hiddenIds = shallowRef<Set<string>>(new Set());
  const flyTo = ref<Feature | null>(null);
  const selection = ref<object | null>(null);
  const drawMode = ref<"polygon" | "rect" | "circle" | null>(null);
  const layerStamp = ref<LayerStamp | null>(null);
  const viewLayer = ref<string | null>(null);
  const commitProgress = ref<{ done: number; total: number; pct: number } | null>(null);
  const loadProgress = ref<{ done: number; total: number; pct: number } | null>(null);

  function applyCollections(data: { sites: FeatureCollection; roads: FeatureCollection; lessors: FeatureCollection; areas: FeatureCollection }) {
    sites.value = rawFc(data.sites);
    roads.value = rawFc(data.roads);
    lessors.value = rawFc(data.lessors);
    areas.value = rawFc(data.areas);
    featureById.value = indexFeatures(sites.value, roads.value, lessors.value, areas.value);
    const layers = buildLayerMaps(sites.value, roads.value, lessors.value, areas.value);
    layerIds.value = layers.ids;
    layerFeats.value = layers.feats;
  }

  function log(level: "info" | "warn" | "error", msg: string) {
    logs.value = [...logs.value.slice(-49), { ts: new Date().toISOString().slice(11, 19), level, msg }];
  }

  async function checkAuth() {
    authChecking.value = true;
    if (!api.getToken()) { currentUser.value = null; authChecking.value = false; return; }
    try { currentUser.value = await api.fetchMe(); }
    catch { api.clearToken(); currentUser.value = null; }
    finally { authChecking.value = false; }
  }

  async function doLogin(u: string, p: string) {
    const r = await api.login(u, p);
    currentUser.value = r.user;
  }
  async function doLogout() {
    await api.logout();
    currentUser.value = null;
  }

  function setLoadProgress(done: number, total: number) {
    loadProgress.value = { done, total, pct: total > 0 ? Math.round((done * 100) / total) : 0 };
  }

  function finishLoading() {
    loadProgress.value = null;
    if (phase.value === "loading") phase.value = "idle";
  }

  async function refresh() {
    phase.value = "loading";
    setLoadProgress(0, 1);
    try {
      const data = await api.fetchAll();
      applyCollections(data);
      try { const h = await api.health(); dbOk.value = !!h.db; } catch { dbOk.value = false; }
      log("info", t("log.refresh_ok", { s: sites.value.features.length, r: roads.value.features.length, l: lessors.value.features.length }));
      const n = sites.value.features.length + roads.value.features.length + lessors.value.features.length + areas.value.features.length;
      if (n === 0) finishLoading();
    } catch (e) {
      log("error", t("log.refresh_err", { msg: e instanceof Error ? e.message : String(e) }));
      finishLoading();
    }
  }

  async function refreshBaseline() {
    try { baseline.value = await api.fetchBaselineState(); }
    catch (e) { log("error", t("log.baseline_err", { msg: (e as Error).message })); }
  }

  function pickFile(file: File, stamp: LayerStamp) {
    if (file.size > MAX_FILE) {
      log("error", t("log.file_too_large", { name: file.name, size: (file.size / 1024 / 1024).toFixed(1), limit: 100 }));
      return;
    }
    layerStamp.value = stamp;
    void startUpload(file, stamp);
  }

  async function startUpload(file: File, stamp: LayerStamp) {
    phase.value = "uploading";
    importFile.value = file.name;
    log("info", t("log.upload_start", { name: file.name }));
    try {
      const res = await api.uploadFile(file, stamp);
      importSession.value = res.session_id;
      cleanings.value = res.cleanings ?? [];
      baselineRegion.value = res.baseline_region;
      phase.value = "cleaning";
      log("info", t("log.parse_ok", {
        count: res.summary?.total_parsed ?? 0,
        groups: (res.summary?.intra_file_duplicates?.site_groups ?? 0) + (res.summary?.intra_file_duplicates?.lessor_groups ?? 0),
        discarded: (res.summary?.intra_file_duplicates?.site_discarded ?? 0) + (res.summary?.intra_file_duplicates?.lessor_discarded ?? 0),
        cleanings: res.cleanings?.length ?? 0,
      }));
    } catch (e) {
      log("error", t("log.upload_err", { msg: (e as Error).message }));
      phase.value = "idle";
    }
  }

  async function goConflicts(decisions: { row_id: string; action: "auto_fix" | "keep" | "discard" }[]) {
    if (!importSession.value) return;
    try {
      const res = await api.proceedToConflicts(importSession.value, decisions);
      conflicts.value = res.conflicts ?? [];
      cleaningDecisions.value = Object.fromEntries(decisions.map(d => [d.row_id, d.action]));
      phase.value = "conflicts";
      log("info", t("log.cleaning_saved", {
        af: res.cleaning_stats?.auto_fixed ?? 0, k: res.cleaning_stats?.kept ?? 0, d: res.cleaning_stats?.discarded ?? 0,
        count: res.conflicts?.length ?? 0,
      }));
    } catch (e) {
      log("error", t("log.conflict_err", { msg: (e as Error).message }));
    }
  }

  async function goBackCleaning() {
    if (!importSession.value) return;
    try {
      const res = await api.backToCleaning(importSession.value);
      cleanings.value = res.cleanings;
      baselineRegion.value = res.baseline_region;
      cleaningDecisions.value = res.cleaning_decisions ?? {};
      phase.value = "cleaning";
    } catch (e) {
      log("error", t("log.back_err", { msg: (e as Error).message }));
    }
  }

  async function doCommit(decisions: { key: string; action: "overwrite" | "ignore" }[]) {
    if (!importSession.value) return;
    phase.value = "committing";
    const sid = importSession.value;
    const timer = setInterval(async () => {
      try { commitProgress.value = await api.fetchImportProgress(sid); } catch { /* ignore */ }
    }, 400);
    try {
      const res = await api.commitImport(sid, decisions);
      log("info", t("log.import_done"));
      if (res.baseline_established) {
        log("info", t("log.baseline_fixed", {
          name: res.baseline_established.name_zh || res.baseline_established.iso_a2,
          iso: res.baseline_established.iso_a2,
          pct: res.baseline_established.coverage_pct,
        }));
      }
      importSession.value = null;
      await refresh();
      await refreshBaseline();
    } catch (e) {
      log("error", t("log.commit_err", { msg: (e as Error).message }));
    } finally {
      clearInterval(timer);
      commitProgress.value = null;
      phase.value = "idle";
    }
  }

  async function doCancel(downloadExcel = false) {
    if (!importSession.value) return;
    try {
      if (downloadExcel) {
        try { await api.downloadConflictsXlsx(importSession.value); }
        catch (e) { log("error", t("log.cancel_excel_err", { msg: (e as Error).message })); }
      }
      await api.cancelImport(importSession.value);
      log("info", t("log.cancel_ok", { file: importFile.value, extra: downloadExcel ? t("log.cancel_ok_extra") : "" }));
    } catch (e) {
      log("error", t("log.cancel_err", { msg: (e as Error).message }));
    } finally {
      importSession.value = null;
      phase.value = "idle";
    }
  }

  function search(q: string) {
    const query = q.trim();
    if (!query) { searchResults.value = null; return; }
    const low = query.toLowerCase();
    const results: Feature[] = [];
    let total = 0;
    const scan = (list: Feature[]) => {
      for (const f of list) {
        if (!nameOf(f).toLowerCase().includes(low)) continue;
        total++;
        if (results.length < SEARCH_CAP) results.push(f);
      }
    };
    scan(sites.value.features);
    scan(roads.value.features);
    scan(lessors.value.features);
    scan(areas.value.features);
    searchResults.value = { query, total, results };
    if (results[0]) flyToFeature(results[0]);
  }

  function flyToFeature(f: Feature) {
    selected.value = f;
    flyTo.value = f;
  }

  function toggleHidden(key: string, on?: boolean) {
    hidden.value = { ...hidden.value, [key]: on ?? !hidden.value[key] };
  }

  function setKindVisible(ids: string[], visible: boolean) {
    const next = new Set(hiddenIds.value);
    for (const id of ids) {
      if (visible) next.delete(id);
      else next.add(id);
    }
    hiddenIds.value = markRaw(next);
  }

  return {
    currentUser, authChecking, sites, roads, lessors, areas, selected, logs, phase, dbOk, baseline,
    importSession, cleanings, conflicts, cleaningDecisions, conflictDecisions, baselineRegion,
    importFile, progress, searchResults, hidden, hiddenIds, featureById, layerIds, layerFeats, flyTo, selection, drawMode, layerStamp, viewLayer,
    commitProgress, loadProgress,
    log, checkAuth, doLogin, doLogout, refresh, refreshBaseline, setLoadProgress, finishLoading, pickFile, goConflicts, goBackCleaning,
    doCommit, doCancel, search, flyToFeature, toggleHidden, setKindVisible,
  };
});
