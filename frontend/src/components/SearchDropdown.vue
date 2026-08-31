<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";
import { nameOf } from "../ol/utils";
import type { Feature } from "../api";

const { t } = useI18n();
const store = useAppStore();

const KIND_LABEL: Record<string, string> = { site: "Site", road: "Road", lessor: "Lessor", area: "Area" };

function kindOf(f: Feature): string {
  const k = f.properties?.kind;
  return typeof k === "string" ? k : "unknown";
}
function nonEmpty(v: unknown): string | null {
  if (v == null) return null;
  const s = String(v).trim();
  return s === "" ? null : s;
}
function fmtCoord(lat: number, lng: number): string {
  return `(${lat.toFixed(5)}, ${lng.toFixed(5)})`;
}
function firstCoord(geom: Feature["geometry"]): { lat: number; lng: number } | null {
  if (!geom) return null;
  let c: unknown = geom.coordinates;
  while (Array.isArray(c) && Array.isArray(c[0])) c = c[0];
  if (Array.isArray(c) && c.length >= 2) {
    const lng = Number(c[0]);
    const lat = Number(c[1]);
    if (Number.isFinite(lat) && Number.isFinite(lng)) return { lat, lng };
  }
  return null;
}
function coreInfo(f: Feature): string {
  const p = f.properties ?? {};
  const k = kindOf(f);
  const segs: string[] = [];
  if (k === "site") {
    const status = nonEmpty(p.site_status);
    const project = nonEmpty(p.project);
    if (status) segs.push(`Status: ${status}`);
    if (project) segs.push(`Project: ${project}`);
    const lat = Number(p.lati);
    const lng = Number(p.longi);
    if (Number.isFinite(lat) && Number.isFinite(lng)) segs.push(fmtCoord(lat, lng));
  } else if (k === "lessor") {
    const cat = nonEmpty(p.lessor_category);
    const rel = nonEmpty(p.relationship);
    if (cat) segs.push(`Cat: ${cat}`);
    if (rel) segs.push(`Rel: ${rel}`);
  } else if (k === "road") {
    const c = firstCoord(f.geometry);
    if (c) segs.push(`Start: ${fmtCoord(c.lat, c.lng)}`);
  }
  return segs.join(" · ");
}

function rowTip(f: Feature): string {
  const name = nameOf(f);
  const info = coreInfo(f);
  const kind = KIND_LABEL[kindOf(f)] ?? kindOf(f);
  return [name, info, kind].filter(Boolean).join(" · ");
}

const resultRows = computed(() => {
  const res = store.searchResults;
  if (!res) return [];
  return res.results.map((f, i) => ({
    f,
    i,
    name: nameOf(f),
    info: coreInfo(f),
    kind: kindOf(f),
    tip: rowTip(f),
  }));
});

function onDoc(e: MouseEvent) {
  const container = document.querySelector(".toolbar .search");
  if (container && !container.contains(e.target as Node)) store.searchResults = null;
}

onMounted(() => document.addEventListener("mousedown", onDoc));
onBeforeUnmount(() => document.removeEventListener("mousedown", onDoc));
</script>

<template>
  <div v-if="store.searchResults" class="search-dropdown">
    <div class="body-head">
      <span class="muted">
        {{ t("op.search.header") }}{{ store.searchResults.total > 0 ? `（${store.searchResults.total}）` : "" }}
      </span>
      <button class="clear-btn" type="button" @click.stop="store.searchResults = null" :title="t('op.search.clear.tip')">
        {{ t("op.search.clear.btn") }}
      </button>
    </div>
    <div v-if="store.searchResults.total === 0" class="row error">{{ t("op.search.none") }}</div>
    <template v-else>
      <div class="search-summary" @click="store.searchResults.results[0] && store.flyToFeature(store.searchResults.results[0])">
        {{ t("op.search.summary", { count: store.searchResults.total }) }}
      </div>
      <div
        v-for="row in resultRows"
        :key="`${row.f.id}-${row.i}`"
        class="search-result-row"
        :title="row.tip"
        @click="store.flyToFeature(row.f)"
      >
        <span class="sr-name" :title="row.name">{{ row.name }}</span>
        <span v-if="row.info" class="sr-info" :title="row.info">{{ row.info }}</span>
        <span class="sr-badge" :class="'sr-' + row.kind" :title="KIND_LABEL[row.kind] ?? row.kind">{{ KIND_LABEL[row.kind] ?? "?" }}</span>
      </div>
      <div v-if="store.searchResults.total > store.searchResults.results.length" class="row muted">
        {{ t("op.search.capped", { cap: store.searchResults.results.length }) }}
      </div>
    </template>
  </div>
</template>
