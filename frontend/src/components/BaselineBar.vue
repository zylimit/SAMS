<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";

const { t, locale } = useI18n();
const store = useAppStore();

function fmtDate(iso?: unknown): string {
  if (!iso) return "—";
  const m = String(iso).match(/^(\d{4}-\d{2}-\d{2})/);
  return m ? m[1] : String(iso);
}

const established = computed(() => !!(store.baseline && store.baseline.established));
const countryName = computed(() => {
  const b = store.baseline;
  if (!b) return "";
  return (locale.value === "zh" ? b.name_zh : b.name_en) || b.name_zh || b.iso_a2;
});
</script>

<template>
  <div v-if="established && store.baseline" class="baseline-bar established">
    <span class="baseline-icon">📍</span>
    <span class="baseline-text">
      {{ t("bs.country") }}
      <b>{{ countryName }}</b>
      <span class="baseline-iso">({{ store.baseline.iso_a2 }})</span>
      · {{ t("bs.established") }}
      <span class="baseline-date">{{ fmtDate(store.baseline.established_at) }}</span>
      <span v-if="store.baseline.coverage_pct != null" class="baseline-meta">
        · {{ store.baseline.coverage_pct }}% {{ t("bs.coverage") }} / {{ store.baseline.points_used }} {{ t("bs.points") }}
      </span>
    </span>
  </div>
  <div v-else class="baseline-bar pending">
    <span class="baseline-icon">📍</span>
    <span class="baseline-text">{{ t("bs.pending") }}</span>
  </div>
</template>
