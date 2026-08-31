<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  CircleDot, Download, History, Languages, LogOut, Moon, RefreshCw, Search,
  Settings, SquareDashedMousePointer, Sun, Tag, Trash2, TriangleAlert, Type,
} from "lucide-vue-next";
import { useAppStore } from "../stores/app";
import { setLang } from "../i18n";
import { NP_RADIUS_KEY, NP_RADIUS_OPTIONS, OPERATOR_LETTERS_KEY, POLYGON_LABELS_KEY } from "../ol/utils";
import SearchDropdown from "./SearchDropdown.vue";

const ICON = 20;

const props = defineProps<{
  npRadius: number;
  showLabels: boolean;
  showLetters: boolean;
}>();
const emit = defineEmits<{
  "update:npRadius": [n: number];
  "update:showLabels": [b: boolean];
  "update:showLetters": [b: boolean];
  exportAll: [];
  exportSel: [];
  clearBaseline: [];
  restore: [];
  delhist: [];
  admin: [];
}>();

const { t, locale } = useI18n();
const store = useAppStore();
const searchQ = defineModel<string>("search", { default: "" });
const searchRef = ref<HTMLInputElement | null>(null);
const searchFocused = ref(false);
const ddMaxPx = ref(480);
const openMenu = ref<"export" | "draw" | null>(null);
const rootRef = ref<HTMLElement | null>(null);
const theme = ref(document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark");
let mapRo: ResizeObserver | null = null;

const user = computed(() => store.currentUser);
const isAdmin = computed(() => !!user.value?.is_admin);
const canExport = computed(() => isAdmin.value || !!user.value?.perms?.export);
const canEditDelete = computed(() => isAdmin.value || !!user.value?.perms?.edit_delete);
const canDanger = computed(() => isAdmin.value || !!user.value?.perms?.danger);
const busy = computed(() => store.phase === "uploading" || store.phase === "committing" || store.phase === "loading");

const drawLabel = computed(() => {
  if (store.drawMode === "polygon") return t("tb.draw.active.poly");
  if (store.drawMode === "circle") return t("tb.draw.active.circle");
  if (store.drawMode === "rect") return t("tb.draw.active.rect");
  return t("tb.draw.label");
});

function onDoc(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) openMenu.value = null;
}
function readMapSpan() {
  const mapEl = document.querySelector(".app .map") as HTMLElement | null;
  const searchEl = document.querySelector(".toolbar .search") as HTMLElement | null;
  const mapW = mapEl?.clientWidth ?? 0;
  const cap = mapW > 0 ? mapW : 480;
  if (!searchEl) {
    ddMaxPx.value = Math.max(280, cap);
    return;
  }
  const r = searchEl.getBoundingClientRect();
  const center = r.left + r.width / 2;
  const half = Math.min(Math.max(0, center - 12), Math.max(0, window.innerWidth - center - 12));
  ddMaxPx.value = Math.max(r.width, Math.min(cap, half * 2));
}
function onSearchWrapDown(e: MouseEvent) {
  const t = e.target as HTMLElement | null;
  if (!t || t.closest("button, .search-dropdown, input")) return;
  e.preventDefault();
  searchRef.value?.focus();
}
onMounted(() => {
  document.addEventListener("mousedown", onDoc);
  readMapSpan();
  const mapEl = document.querySelector(".app .map");
  if (mapEl && typeof ResizeObserver !== "undefined") {
    mapRo = new ResizeObserver(readMapSpan);
    mapRo.observe(mapEl);
  }
  window.addEventListener("resize", readMapSpan);
});
onBeforeUnmount(() => {
  document.removeEventListener("mousedown", onDoc);
  window.removeEventListener("resize", readMapSpan);
  mapRo?.disconnect();
  mapRo = null;
});

function toggleLang() {
  const next = locale.value === "en" ? "zh" : "en";
  setLang(next as "en" | "zh");
  document.documentElement.setAttribute("data-lang", next);
}
function toggleTheme() {
  const next = theme.value === "dark" ? "light" : "dark";
  theme.value = next;
  document.documentElement.setAttribute("data-theme", next);
  try { localStorage.setItem("sams.theme", next); } catch { /* */ }
}
function setDraw(m: "polygon" | "rect" | "circle" | null) {
  store.drawMode = m;
  openMenu.value = null;
}
function onSearch() {
  const q = searchQ.value.trim();
  if (q) store.search(q);
}
async function onRefresh() {
  store.log("info", t("log.refresh_start"));
  try { await store.refresh(); }
  catch (e) { store.log("error", t("log.refresh_err", { msg: e instanceof Error ? e.message : String(e) })); }
}
</script>

<template>
  <div class="toolbar" ref="rootRef">
    <div v-if="canExport" class="dropdown">
      <button class="icon-btn" type="button" :disabled="busy" :title="t('tb.export.tip')" :aria-label="t('tb.export.label')"
        @click="openMenu = openMenu === 'export' ? null : 'export'">
        <Download :size="ICON" :stroke-width="1.7" />
      </button>
      <div v-if="openMenu === 'export'" class="dropdown-menu">
        <div class="dropdown-item" @click="openMenu = null; emit('exportAll')">{{ t("tb.export.all") }}</div>
        <div
          class="dropdown-item"
          :class="{ disabled: !store.selection }"
          :title="store.selection ? '' : t('tb.export.nosel.tip')"
          @click="store.selection && (openMenu = null, emit('exportSel'))"
        >
          {{ store.selection ? t("tb.export.selection") : t("tb.export.nosel.txt") }}
        </div>
      </div>
    </div>

    <div class="dropdown">
      <button
        type="button"
        class="icon-btn"
        :class="{ active: !!store.drawMode }"
        :disabled="busy"
        :title="drawLabel"
        :aria-label="drawLabel"
        @click="openMenu = openMenu === 'draw' ? null : 'draw'"
      >
        <SquareDashedMousePointer :size="ICON" :stroke-width="1.7" />
      </button>
      <div v-if="openMenu === 'draw'" class="dropdown-menu">
        <div class="dropdown-item" @click="setDraw('polygon')">{{ t("tb.draw.polygon") }}</div>
        <div class="dropdown-item" @click="setDraw('rect')">{{ t("tb.draw.rect") }}</div>
        <div class="dropdown-item" @click="setDraw('circle')">{{ t("tb.draw.circle") }}</div>
        <div v-if="store.drawMode || store.selection" class="dropdown-item danger" @click="store.selection = null; setDraw(null)">
          {{ t("tb.draw.clear") }}
        </div>
      </div>
    </div>

    <button class="icon-btn" type="button" :disabled="busy" :title="t('tb.refresh.tip')" :aria-label="t('tb.refresh.label')" @click="onRefresh">
      <RefreshCw :size="ICON" :stroke-width="1.7" />
    </button>

    <label class="np-radius" :title="t('tb.np_radius.tip')">
      <span class="np-radius-icon" :aria-label="t('tb.np_radius.label')">
        <CircleDot :size="ICON" :stroke-width="1.7" />
      </span>
      <select :value="npRadius" @change="e => { const n = Number((e.target as HTMLSelectElement).value); emit('update:npRadius', n); try { localStorage.setItem(NP_RADIUS_KEY, String(n)); } catch {} }">
        <option v-for="n in NP_RADIUS_OPTIONS" :key="n" :value="n">{{ n }}m</option>
      </select>
    </label>

    <button
      class="icon-btn"
      :class="{ active: showLabels }"
      type="button"
      :title="t('tb.polygon_labels.tip')"
      :aria-label="t('tb.polygon_labels.label')"
      @click="emit('update:showLabels', !showLabels); try { localStorage.setItem(POLYGON_LABELS_KEY, !showLabels ? '1' : '0'); } catch {}"
    >
      <Tag :size="ICON" :stroke-width="1.7" />
    </button>

    <button
      class="icon-btn"
      :class="{ active: showLetters }"
      type="button"
      :title="t('tb.operator_letters.tip')"
      :aria-label="t('tb.operator_letters.label')"
      @click="emit('update:showLetters', !showLetters); try { localStorage.setItem(OPERATOR_LETTERS_KEY, !showLetters ? '1' : '0'); } catch {}"
    >
      <Type :size="ICON" :stroke-width="1.7" />
    </button>

    <div
      class="search"
      :style="{ '--dd-max': `${ddMaxPx}px` }"
      @mousedown="onSearchWrapDown"
    >
      <div class="search-shell" :class="{ 'is-on': searchFocused }">
        <input
          id="tb-search"
          ref="searchRef"
          v-model="searchQ"
          :placeholder="t('tb.search.placeholder')"
          :title="searchQ.trim() || t('tb.search.tip')"
          @mousedown.stop
          @focus="searchFocused = true"
          @blur="searchFocused = false"
          @keydown.enter="onSearch"
        />
      </div>
      <button class="icon-btn" type="button" :disabled="!searchQ.trim()" :title="t('tb.search.tip')" :aria-label="t('tb.search.btn')" @click="onSearch">
        <Search :size="ICON" :stroke-width="1.7" />
      </button>
      <SearchDropdown />
    </div>

    <button v-if="canDanger" class="icon-btn danger" type="button" :disabled="busy" :title="t('tb.clear.tip')" :aria-label="t('tb.clear.label')" @click="emit('clearBaseline')">
      <TriangleAlert :size="ICON" :stroke-width="1.7" />
    </button>
    <button v-if="canDanger" class="icon-btn" type="button" :disabled="busy" :title="t('tb.restore.tip')" :aria-label="t('tb.restore.label')" @click="emit('restore')">
      <History :size="ICON" :stroke-width="1.7" />
    </button>
    <button v-if="canEditDelete" class="icon-btn" type="button" :disabled="busy" :title="t('tb.delhist.tip')" :aria-label="t('tb.delhist.label')" @click="emit('delhist')">
      <Trash2 :size="ICON" :stroke-width="1.7" />
    </button>

    <span class="tb-username" :title="user?.username">{{ user?.username }}</span>
    <button v-if="isAdmin" class="icon-btn" type="button" :title="t('tb.admin.tip')" :aria-label="t('tb.admin.label')" @click="emit('admin')">
      <Settings :size="ICON" :stroke-width="1.7" />
    </button>
    <button class="icon-btn" type="button" :title="t('tb.logout.tip')" :aria-label="t('tb.logout.label')" @click="store.doLogout()">
      <LogOut :size="ICON" :stroke-width="1.7" />
    </button>
    <button
      class="icon-btn theme-toggle"
      type="button"
      :title="theme === 'dark' ? t('tb.theme.to_light') : t('tb.theme.to_dark')"
      :aria-label="theme === 'dark' ? t('tb.theme.to_light') : t('tb.theme.to_dark')"
      @click="toggleTheme"
    >
      <Sun v-if="theme === 'dark'" :size="ICON" :stroke-width="1.7" />
      <Moon v-else :size="ICON" :stroke-width="1.7" />
    </button>
    <button class="icon-btn lang-toggle" type="button" :title="t('tb.lang.tip')" :aria-label="t('tb.lang.tip')" @click="toggleLang">
      <Languages :size="ICON" :stroke-width="1.7" />
    </button>
  </div>
</template>
