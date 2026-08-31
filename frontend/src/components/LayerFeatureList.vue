<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";
import { deleteSites, exportSelectionIds, updateSite } from "../api";
import { nameOf, statusBucket } from "../ol/utils";
import type { Feature } from "../api";

const ROW_H = 30;
const HEAD_H = 32;
const OVERSCAN = 8;

const { t } = useI18n();
const store = useAppStore();
const filter = ref("");
const checked = shallowRef<Set<string>>(new Set());
const editing = ref<Feature | null>(null);
const editProject = ref("");
const editStatus = ref("");
const editErr = ref("");
const scrollEl = ref<HTMLElement | null>(null);
const scrollTop = ref(0);
const viewportH = ref(360);
let ro: ResizeObserver | null = null;

const parsed = computed(() => {
  const key = store.viewLayer;
  if (!key) return null;
  if (key === "road") return { kind: "road" as const, indexKey: "road", title: t("lfl.kind.road") };
  if (key === "lessor") return { kind: "lessor" as const, indexKey: "lessor", title: t("lfl.kind.lessor") };
  if (key.startsWith("area:")) {
    const op = key.slice(5);
    return { kind: "area" as const, indexKey: `area/${op}`, title: `${t("lfl.kind.area")} · ${op}` };
  }
  const parts = key.split(":");
  if (parts[0] === "site") {
    const [, op, cat, ty, st] = parts;
    const indexKey = st ? `${op}/${cat}/${ty}/${st}` : `${op}/${cat}/${ty}`;
    return { kind: "site" as const, indexKey, title: [op, cat, ty, st].filter(Boolean).join(" · ") };
  }
  return null;
});

const all = computed(() => {
  const p = parsed.value;
  if (!p) return [];
  if (store.layerFeats.size > 0) return store.layerFeats.get(p.indexKey) ?? [];
  if (p.kind === "road") return store.roads.features;
  if (p.kind === "lessor") return store.lessors.features;
  if (p.kind === "area") {
    const op = p.indexKey.slice(5);
    return store.areas.features.filter(f => String(f.properties?.operator ?? "") === op);
  }
  const [op, cat, ty, st] = p.indexKey.split("/");
  return store.sites.features.filter(f => {
    const pr = f.properties ?? {};
    if (String(pr.operator ?? "") !== op || String(pr.category ?? "") !== cat || String(pr.type ?? "") !== ty) return false;
    return st ? statusBucket(pr.site_status) === st : true;
  });
});

const features = computed(() => {
  const q = filter.value.trim().toLowerCase();
  return q ? all.value.filter(f => nameOf(f).toLowerCase().includes(q)) : all.value;
});

const canEdit = computed(() => parsed.value?.kind === "site" && (store.currentUser?.is_admin || store.currentUser?.perms?.edit_delete));

const visible = computed(() => {
  const list = features.value;
  const total = list.length;
  const start = Math.max(0, Math.floor((scrollTop.value - HEAD_H) / ROW_H) - OVERSCAN);
  const end = Math.min(total, Math.ceil((scrollTop.value - HEAD_H + viewportH.value) / ROW_H) + OVERSCAN);
  return { start, end, total, rows: list.slice(start, end) };
});

const selectedId = computed(() => store.selected ? String(store.selected.id) : "");
const allFilteredSelected = computed(() => {
  const list = features.value;
  if (!canEdit.value || !list.length) return false;
  return list.every(f => checked.value.has(String(f.id)));
});
const someFilteredSelected = computed(() => {
  if (!canEdit.value) return false;
  return features.value.some(f => checked.value.has(String(f.id)));
});

function fid(f: Feature) { return String(f.id); }
function toggleRow(f: Feature) {
  const next = new Set(checked.value);
  if (next.has(fid(f))) next.delete(fid(f));
  else next.add(fid(f));
  checked.value = next;
}
function toggleAllFiltered() {
  const next = new Set(checked.value);
  if (allFilteredSelected.value) for (const f of features.value) next.delete(fid(f));
  else for (const f of features.value) next.add(fid(f));
  checked.value = next;
}
function selectedFeatures() {
  return all.value.filter(f => checked.value.has(fid(f)));
}
function onScroll(e: Event) {
  scrollTop.value = (e.currentTarget as HTMLElement).scrollTop;
}
function cell(f: Feature, k: string) {
  const v = f.properties?.[k];
  return v == null || v === "" ? "—" : String(v);
}

function startEdit(f: Feature) {
  editing.value = f;
  editProject.value = String(f.properties?.project ?? "");
  editStatus.value = String(f.properties?.site_status ?? "");
  editErr.value = "";
}

async function save() {
  const f = editing.value;
  if (!f?.properties) return;
  const p = f.properties;
  editErr.value = "";
  try {
    await updateSite({ site_id: String(p.site_id), option: String(p.option ?? "") }, {
      project: editProject.value, site_status: editStatus.value, lati: p.lati, longi: p.longi,
    });
    store.log("info", t("log.edit_site_ok", { id: p.site_id }));
    editing.value = null;
    await store.refresh();
  } catch (e) {
    editErr.value = e instanceof Error ? e.message : String(e);
  }
}
async function del() {
  const keys = selectedFeatures().map(f => ({ site_id: String(f.properties!.site_id), option: String(f.properties!.option ?? "") }));
  if (!keys.length) return;
  const r = await deleteSites(keys);
  store.log("info", t("log.delete_site_ok", { n: r.deleted }));
  checked.value = new Set();
  await store.refresh();
}
async function exp() {
  const keys = selectedFeatures().map(f => ({ site_id: String(f.properties!.site_id), option: String(f.properties!.option ?? "") }));
  if (!keys.length) return;
  await exportSelectionIds(keys);
}

watch(scrollEl, (el) => {
  ro?.disconnect();
  ro = null;
  if (!el) return;
  viewportH.value = el.clientHeight;
  ro = new ResizeObserver(() => { viewportH.value = el.clientHeight; });
  ro.observe(el);
});

watch(() => store.viewLayer, async () => {
  filter.value = "";
  scrollTop.value = 0;
  checked.value = new Set();
  editing.value = null;
  await nextTick();
  if (scrollEl.value) scrollEl.value.scrollTop = 0;
});

watch(() => store.selected?.id, (sid) => {
  if (sid == null || !parsed.value) return;
  const idx = features.value.findIndex(f => String(f.id) === String(sid));
  const el = scrollEl.value;
  if (idx < 0 || !el) return;
  const rowTop = HEAD_H + idx * ROW_H;
  const viewTop = el.scrollTop + HEAD_H;
  const viewBottom = el.scrollTop + el.clientHeight;
  if (rowTop < viewTop || rowTop + ROW_H > viewBottom) {
    el.scrollTop = Math.max(0, HEAD_H + idx * ROW_H - el.clientHeight / 2 + ROW_H / 2);
    scrollTop.value = el.scrollTop;
  }
});

onUnmounted(() => { ro?.disconnect(); ro = null; });
</script>

<template>
  <div v-if="parsed" class="lfl-panel" :style="{ left: '28%', top: '120px', width: '720px', height: '480px' }">
    <div class="lfl-head">
      <span class="lfl-title">{{ parsed.title }}</span>
      <span class="lfl-count">
        {{ filter.trim() ? t("lfl.count.filtered", { shown: features.length, total: all.length }) : t("lfl.count", { total: all.length }) }}
      </span>
      <button class="lfl-close" type="button" @click="store.viewLayer = null">{{ t("lfl.close") }}</button>
    </div>
    <div class="lfl-filter">
      <input v-model="filter" :placeholder="t('lfl.filter.placeholder')" />
    </div>
    <div v-if="canEdit" class="lfl-actions">
      <span class="lfl-sel-count">{{ t("lfl.sel.count", { n: checked.size }) }}</span>
      <button class="lfl-act danger" type="button" :disabled="!checked.size" @click="del">{{ t("lfl.del.btn") }}</button>
      <button class="lfl-act" type="button" :disabled="!checked.size" @click="exp">{{ t("lfl.exp.btn") }}</button>
    </div>
    <div v-if="!features.length" class="lfl-empty">
      {{ all.length ? t("lfl.empty.filtered") : t("lfl.empty") }}
    </div>
    <div v-else class="lfl-scroll" ref="scrollEl" @scroll="onScroll">
      <div class="lfl-table" style="width: 100%">
        <div class="lfl-thead" :style="{ height: HEAD_H + 'px' }">
          <div v-if="canEdit" class="lfl-th lfl-check" style="flex: 0 0 36px">
            <input
              type="checkbox"
              :checked="allFilteredSelected"
              :ref="el => { const n = el as HTMLInputElement | null; if (n) n.indeterminate = someFilteredSelected && !allFilteredSelected; }"
              :title="t('lfl.sel.all')"
              @change="toggleAllFiltered"
            />
          </div>
          <div class="lfl-th" style="flex: 1 1 140px">{{ parsed.kind === "site" ? t("lfl.col.site_id") : parsed.kind === "road" ? t("lfl.col.property") : parsed.kind === "lessor" ? t("lfl.col.lessor_name") : t("lfl.col.name") }}</div>
          <div v-if="parsed.kind === 'site'" class="lfl-th" style="flex: 0 0 110px">{{ t("lfl.col.site_status") }}</div>
          <div v-if="parsed.kind === 'site'" class="lfl-th" style="flex: 1 1 120px">{{ t("lfl.col.project") }}</div>
          <div v-if="canEdit" class="lfl-th lfl-action" style="flex: 0 0 56px">{{ t("lfl.col.actions") }}</div>
        </div>
        <div class="lfl-virt" :style="{ height: visible.total * ROW_H + 'px', width: '100%' }">
          <div
            v-for="(f, i) in visible.rows"
            :key="fid(f)"
            class="lfl-row"
            :class="{ selected: selectedId === fid(f) }"
            :style="{ transform: `translateY(${(visible.start + i) * ROW_H}px)`, height: ROW_H + 'px', width: '100%' }"
            @click="store.flyToFeature(f)"
          >
            <div v-if="canEdit" class="lfl-cell lfl-check" style="flex: 0 0 36px" @click.stop>
              <input type="checkbox" :checked="checked.has(fid(f))" :aria-label="t('lfl.sel.row')" @change="toggleRow(f)" />
            </div>
            <div class="lfl-cell" style="flex: 1 1 140px">{{ nameOf(f) }}</div>
            <div v-if="parsed.kind === 'site'" class="lfl-cell" style="flex: 0 0 110px">{{ cell(f, "site_status") }}</div>
            <div v-if="parsed.kind === 'site'" class="lfl-cell" style="flex: 1 1 120px">{{ cell(f, "project") }}</div>
            <div v-if="canEdit" class="lfl-cell lfl-action" style="flex: 0 0 56px" @click.stop>
              <button class="lfl-edit-btn" type="button" :title="t('lfl.act.edit')" @click="startEdit(f)">✎</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div v-if="editing" class="modal-mask" @click.self="editing = null">
    <div class="modal lfl-edit-modal">
      <div class="modal-header"><h2>{{ t("lfl.act.edit") }}</h2></div>
      <div class="modal-body lfl-edit-body">
        <label>{{ t("lfl.col.site_id") }}<input :value="String(editing.properties?.site_id ?? '')" disabled /></label>
        <label>{{ t("lfl.col.project") }}<input v-model="editProject" /></label>
        <label>{{ t("lfl.col.site_status") }}<input v-model="editStatus" /></label>
        <div v-if="editErr" class="lfl-edit-err">{{ editErr }}</div>
      </div>
      <div class="modal-footer">
        <button type="button" class="cancel" @click="editing = null">{{ t("lfl.close") }}</button>
        <button type="button" class="primary" @click="save">{{ t("lfl.act.edit") }}</button>
      </div>
    </div>
  </div>
</template>
