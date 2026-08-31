<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "./stores/app";
import { setUnauthorizedHandler } from "./api";
import { clearBaseline, exportAll, exportSelection } from "./api";
import { readNpRadius, readOperatorLetters, readPolygonLabels } from "./ol/utils";
import LoginPage from "./views/LoginPage.vue";
import ForcePassword from "./components/ForcePassword.vue";
import Toolbar from "./components/Toolbar.vue";
import BaselineBar from "./components/BaselineBar.vue";
import LayerTree from "./components/LayerTree.vue";
import MapView from "./components/MapView.vue";
import AttributePanel from "./components/AttributePanel.vue";
import OutputPanel from "./components/OutputPanel.vue";
import CleaningDialog from "./components/CleaningDialog.vue";
import ConflictDialog from "./components/ConflictDialog.vue";
import RestoreDialog from "./components/RestoreDialog.vue";
import DeleteHistory from "./components/DeleteHistory.vue";
import LayerFeatureList from "./components/LayerFeatureList.vue";
import AdminModal from "./components/admin/AdminModal.vue";

const { t } = useI18n();
const store = useAppStore();
const npRadius = ref(readNpRadius());
const showLabels = ref(readPolygonLabels());
const showLetters = ref(readOperatorLetters());
const search = ref("");
const restoreOpen = ref(false);
const delOpen = ref(false);
const adminOpen = ref(false);
const outputOpen = ref(false);
const confirmClear = ref(false);

const authed = computed(() => store.currentUser && !store.currentUser.must_change_password);

const gridStyle = computed(() => {
  const right = store.selected ? "25%" : "0";
  const bottom = outputOpen.value ? "200px" : "28px";
  return {
    gridTemplateColumns: `20% 1fr ${right}`,
    gridTemplateRows: `56px 28px 1fr ${bottom}`,
  };
});

onMounted(async () => {
  setUnauthorizedHandler(() => { store.currentUser = null; });
  await store.checkAuth();
});

watch(authed, async (v) => {
  if (v) {
    await store.refresh();
    await store.refreshBaseline();
  }
}, { immediate: true });

watch(() => store.logs.length, () => {
  const last = store.logs.at(-1);
  if (last && last.level !== "info") outputOpen.value = true;
});

async function onClear() {
  confirmClear.value = false;
  const r = await clearBaseline();
  store.log("info", t("log.clear_ok", { s: r.deleted.site, r: r.deleted.road, l: r.deleted.lessor }));
  await store.refresh();
  await store.refreshBaseline();
}
async function onExportAll() { await exportAll(npRadius.value); }
async function onExportSel() {
  if (!store.selection) return;
  await exportSelection(store.selection, npRadius.value, store.drawMode ?? "polygon");
}
</script>

<template>
  <div v-if="store.authChecking" class="boot-splash">⏳ {{ t("auth.checking") }}</div>
  <LoginPage v-else-if="!store.currentUser" />
  <ForcePassword v-else-if="store.currentUser.must_change_password" />
  <div v-else class="app" :class="{ 'no-attr': !store.selected }" :style="gridStyle">
    <Toolbar
      v-model:np-radius="npRadius"
      v-model:show-labels="showLabels"
      v-model:show-letters="showLetters"
      v-model:search="search"
      @export-all="onExportAll"
      @export-sel="onExportSel"
      @clear-baseline="confirmClear = true"
      @restore="restoreOpen = true"
      @delhist="delOpen = true"
      @admin="adminOpen = true"
    />
    <BaselineBar />
    <LayerTree />
    <MapView :np-radius="npRadius" :show-labels="showLabels" :show-letters="showLetters" />
    <div v-if="store.phase === 'loading'" class="map-loading-overlay">
      <div class="map-loading-box">
        ⏳ {{ t("phase.loading") }}
        <template v-if="store.loadProgress"> · {{ store.loadProgress.pct }}%</template>
      </div>
    </div>
    <AttributePanel />
    <OutputPanel v-model="outputOpen" />
    <CleaningDialog />
    <ConflictDialog />
    <RestoreDialog v-model="restoreOpen" />
    <DeleteHistory v-model="delOpen" />
    <LayerFeatureList />
    <AdminModal v-model="adminOpen" />

    <div v-if="confirmClear" class="modal-mask">
      <div class="modal confirm-modal">
        <div class="modal-header"><h2>{{ t("app.clear.title") }}</h2></div>
        <div class="confirm-body">{{ t("app.clear.body") }}</div>
        <div class="modal-footer">
          <button type="button" class="cancel" @click="confirmClear = false">{{ t("app.clear.cancel") }}</button>
          <button type="button" class="danger" @click="onClear">{{ t("app.clear.confirm") }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
