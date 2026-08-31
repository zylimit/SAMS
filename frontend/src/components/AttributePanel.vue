<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";

const { t } = useI18n();
const store = useAppStore();

const CORE_FIELDS: Record<string, string[]> = {
  site: ["site_id", "option", "project", "site_status", "lati", "longi", "source_file"],
  road: ["id", "property", "source_file"],
  lessor: ["fid", "lessor_name", "lessor_category", "relationship", "source_file"],
  area: ["id", "name", "operator"],
};
const CORE_LABELS: Record<string, string> = {
  site_id: "SITE ID", option: "OPTION", project: "PROJECT", site_status: "SITE STATUS",
  lati: "LATI", longi: "LONGI", id: "ID", property: "Property", fid: "fid",
  lessor_name: "Lessor Name", lessor_category: "Lessor Category", relationship: "Relationship",
  name: "Name", operator: "Operator",
};

function display(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}
function coordWarn(lati: unknown, longi: unknown): boolean {
  const la = Number(lati);
  const lo = Number(longi);
  if (!Number.isFinite(la) || !Number.isFinite(lo)) return false;
  return Math.abs(la) > 90 || Math.abs(lo) > 180;
}

const feature = computed(() => store.selected);
const kind = computed(() => String(feature.value?.properties?.kind ?? ""));
const propsMap = computed(() => (feature.value?.properties ?? {}) as Record<string, unknown>);
const coreFields = computed(() => CORE_FIELDS[kind.value] ?? []);
const extras = computed(() => {
  const skip = new Set([...coreFields.value, "kind"]);
  return Object.entries(propsMap.value).filter(([k]) => !skip.has(k));
});
const warn = computed(() => kind.value === "site" && coordWarn(propsMap.value.lati, propsMap.value.longi));

const title = computed(() => {
  const f = feature.value;
  if (!f) return t("ap.title");
  const p = f.properties ?? {};
  if (p.kind === "site") return `Site: ${p.site_id ?? "?"}${p.option ? " / " + p.option : ""}`;
  if (p.kind === "road") return `Road: ${p.property ?? "#" + p.id}`;
  if (p.kind === "lessor") return `Lessor: ${p.lessor_name ?? p.fid}`;
  if (p.kind === "area") return `Area: ${p.name ?? "#" + p.id}`;
  return t("ap.feature");
});
</script>

<template>
  <aside class="attr">
    <template v-if="!feature">
      <h3>{{ t("ap.title") }}</h3>
      <div class="placeholder">{{ t("ap.placeholder") }}</div>
    </template>
    <template v-else>
      <div class="attr-head">
        <h3>{{ title }}</h3>
        <button class="close" type="button" :title="t('ap.close')" @click="store.selected = null">✖</button>
      </div>
      <div v-if="warn" class="warn-banner">{{ t("ap.coord_warn") }}</div>
      <div class="attr-section">
        <div class="attr-section-title">{{ t("ap.core") }}</div>
        <div
          v-for="k in coreFields"
          :key="k"
          class="attr-row"
          :class="{ highlight: warn && (k === 'lati' || k === 'longi') }"
        >
          <span class="k">{{ k === "source_file" ? t("ap.source") : (CORE_LABELS[k] ?? k) }}</span>
          <span class="v">{{ display(propsMap[k]) }}</span>
        </div>
      </div>
      <div v-if="extras.length" class="attr-section">
        <div class="attr-section-title">{{ t("ap.extras", { n: extras.length }) }}</div>
        <div v-for="[k, v] in extras" :key="k" class="attr-row">
          <span class="k">{{ k }}</span>
          <span class="v">{{ display(v) }}</span>
        </div>
      </div>
    </template>
  </aside>
</template>
