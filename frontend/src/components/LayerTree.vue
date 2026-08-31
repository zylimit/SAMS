<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ChevronDown, ChevronRight, Download, Eye, Folder, FolderOpen } from "lucide-vue-next";
import { useAppStore } from "../stores/app";
import { CATS, OPERATORS, STATUS_COLOR, statusBucket } from "../ol/utils";
import { areaVisible, categoryVisible, lessorVisible, operatorVisible, roadVisible, scopeCtx, siteRootVisible } from "../scopes";
import SiteShapeIcon from "./SiteShapeIcon.vue";
import type { LayerStamp } from "../api";

const { t } = useI18n();
const store = useAppStore();
const ctx = computed(() => scopeCtx(store.currentUser));
const canImport = computed(() => store.currentUser?.is_admin || store.currentUser?.perms?.import);
const busy = computed(() => store.phase !== "idle");
const SITE_STATUSES = ["positive", "negative", "undetermined"] as const;

const expanded = reactive<Record<string, boolean>>({ site: true, road: true, lessor: true });
const highlightedKey = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const pendingStamp = ref<LayerStamp | null>(null);

function isOpen(k: string) { return !!expanded[k]; }
function toggleOpen(k: string) { expanded[k] = !expanded[k]; }

type Tri = "all" | "none" | "partial";
type NodeStat = { ids: string[]; count: number; tri: Tri };
const EMPTY_STAT: NodeStat = { ids: [], count: 0, tri: "all" };

const nodeStats = computed(() => {
  const hidden = store.hiddenIds;
  const src = store.layerIds;
  const out = new Map<string, NodeStat>();
  const add = (key: string, ids: string[]) => {
    let vis = 0;
    for (const id of ids) if (!hidden.has(id)) vis++;
    const tri: Tri = !ids.length || vis === ids.length ? "all" : vis === 0 ? "none" : "partial";
    out.set(key, { ids, count: ids.length, tri });
  };
  for (const [k, ids] of src) add(k, ids);
  for (const op of OPERATORS) {
    const siteIds = src.get(op) ?? [];
    const areaIds = src.get(`area/${op}`) ?? [];
    if (areaIds.length) add(op, siteIds.concat(areaIds));
  }
  return out;
});

function n(key: string): NodeStat {
  return nodeStats.value.get(key) ?? EMPTY_STAT;
}
function triOf(nodeKey: string): Tri {
  return n(nodeKey).tri;
}
function toggleIds(nodeKey: string) {
  store.setKindVisible(n(nodeKey).ids, n(nodeKey).tri === "none");
}
function setIndet(el: unknown, nodeKey: string) {
  const input = el as HTMLInputElement | null;
  if (input) input.indeterminate = n(nodeKey).count > 0 && n(nodeKey).tri === "partial";
}

function openPicker(stamp: LayerStamp) {
  if (busy.value) return;
  pendingStamp.value = stamp;
  fileInput.value?.click();
}
function onFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file && pendingStamp.value) store.pickFile(file, pendingStamp.value);
  pendingStamp.value = null;
  input.value = "";
}

function viewLayer(key: string) {
  store.viewLayer = key;
}

watch(() => store.selected?.id, (sid) => {
  if (sid == null) { highlightedKey.value = null; return; }
  const f = store.featureById.get(String(sid));
  if (!f) { highlightedKey.value = null; return; }
  const p = f.properties ?? {};
  const kind = String(p.kind ?? "");
  if (kind === "site" || p.site_id != null) {
    const op = String(p.operator ?? "");
    const cat = String(p.category ?? "");
    const tp = String(p.type ?? "");
    const st = statusBucket(p.site_status);
    highlightedKey.value = `${op}/${cat}/${tp}/${st}`;
    expanded.site = true;
    expanded[op] = true;
    expanded[`${op}/${cat}`] = true;
    expanded[`${op}/${cat}/${tp}`] = true;
    return;
  }
  if (kind === "road") {
    highlightedKey.value = "road/__style__";
    expanded.road = true;
    return;
  }
  if (kind === "lessor") {
    highlightedKey.value = `lessor/${String(p.relationship ?? "")}`;
    expanded.lessor = true;
    return;
  }
  if (kind === "area") {
    const op = String(p.operator ?? "");
    highlightedKey.value = `area/${op}/AREA`;
    expanded.site = true;
    expanded[op] = true;
    expanded[`area/${op}`] = true;
    return;
  }
  highlightedKey.value = null;
});
</script>

<template>
  <div class="tree">
    <input ref="fileInput" type="file" accept=".kml,.kmz,.xlsx" style="display:none" @change="onFile" />
    <div class="tree-scroll">
      <template v-if="siteRootVisible(ctx)">
        <!-- Site root -->
        <h3 class="folder-row" :style="{ paddingLeft: '4px' }" :title="t('lt.folder.toggle.tip')" @click="toggleIds('site')">
          <span class="folder-disclose" :class="isOpen('site') ? 'open' : 'closed'" :title="isOpen('site') ? t('lt.folder.collapse') : t('lt.folder.expand')" @click.stop="toggleOpen('site')">
            <ChevronDown v-if="isOpen('site')" :size="14" />
            <ChevronRight v-else :size="14" />
          </span>
          <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, 'site')"
            :checked="n('site').count > 0 && triOf('site') === 'all'" :disabled="!n('site').count"
            @change="toggleIds('site')" @click.stop />
          <span class="node-type-icon"><FolderOpen v-if="isOpen('site')" :size="14" /><Folder v-else :size="14" /></span>
          <span class="folder-title">{{ t("lt.tree.site") }}</span>
        </h3>

        <template v-if="isOpen('site')">
          <template v-for="op in OPERATORS.filter(o => operatorVisible(ctx, o))" :key="op">
            <h3 class="folder-row" :style="{ paddingLeft: '20px' }" :title="t('lt.folder.toggle.tip')" @click="toggleIds(op)">
              <span class="folder-disclose" :class="isOpen(op) ? 'open' : 'closed'" @click.stop="toggleOpen(op)">
                <ChevronDown v-if="isOpen(op)" :size="14" /><ChevronRight v-else :size="14" />
              </span>
              <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, op)"
                :checked="n(op).count > 0 && triOf(op) === 'all'" :disabled="!n(op).count"
                @change="toggleIds(op)" @click.stop />
              <span class="node-type-icon"><FolderOpen v-if="isOpen(op)" :size="14" /><Folder v-else :size="14" /></span>
              <span class="folder-title">{{ t(`lt.tree.op.${op}`) }}</span>
              <span class="folder-count">{{ n(op).count }}</span>
            </h3>

            <template v-if="isOpen(op)">
              <template v-if="areaVisible(ctx, op)">
                <h3 class="folder-row" :style="{ paddingLeft: '36px' }" @click="toggleIds('area/' + op)">
                  <span class="folder-disclose" @click.stop="toggleOpen('area/' + op)">
                    <ChevronDown v-if="isOpen('area/' + op)" :size="14" /><ChevronRight v-else :size="14" />
                  </span>
                  <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, 'area/' + op)"
                    :checked="n('area/' + op).count > 0 && triOf('area/' + op) === 'all'" :disabled="!n('area/' + op).count"
                    @change="toggleIds('area/' + op)" @click.stop />
                  <span class="node-type-icon"><FolderOpen v-if="isOpen('area/' + op)" :size="14" /><Folder v-else :size="14" /></span>
                  <span class="folder-title">{{ t("lt.tree.area") }}</span>
                  <span class="folder-count">{{ n('area/' + op).count }}</span>
                </h3>
                <div v-if="isOpen('area/' + op)" class="layer-row" :class="{ 'node-highlighted': highlightedKey === 'area/' + op + '/AREA' }" :style="{ paddingLeft: '52px' }">
                  <span class="folder-disclose leaf-spacer" aria-hidden="true" />
                  <input type="checkbox" class="folder-cb-native" :checked="n('area/' + op).count > 0 && triOf('area/' + op) === 'all'" :disabled="!n('area/' + op).count" @change="toggleIds('area/' + op)" />
                  <span class="node-type-icon"><SiteShapeIcon :stamp="{ operator: op, target_kind: 'area' }" :size="14" /></span>
                  <span class="folder-title layer-label">{{ t("lt.tree.area") }}</span>
                  <div class="layer-actions">
                    <button v-if="canImport" class="layer-btn layer-btn-icon" type="button" :disabled="busy" :title="t('lt.btn.import_layer.tip')" @click="openPicker({ operator: op, target_kind: 'area' })">
                      <Download :size="14" :stroke-width="1.8" />
                    </button>
                    <button class="layer-btn layer-btn-icon" type="button" :disabled="busy || !n('area/' + op).count" :title="t('lt.btn.view_features.tip')" @click="viewLayer('area:' + op)">
                      <Eye :size="14" :stroke-width="1.8" />
                    </button>
                  </div>
                  <span class="folder-count layer-count">{{ n('area/' + op).count }}</span>
                </div>
              </template>

              <template v-for="cat in CATS.filter(c => categoryVisible(ctx, op, c.id))" :key="op + cat.id">
                <h3 class="folder-row" :style="{ paddingLeft: '36px' }" @click="toggleIds(op + '/' + cat.id)">
                  <span class="folder-disclose" @click.stop="toggleOpen(op + '/' + cat.id)">
                    <ChevronDown v-if="isOpen(op + '/' + cat.id)" :size="14" /><ChevronRight v-else :size="14" />
                  </span>
                  <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, op + '/' + cat.id)"
                    :checked="n(op + '/' + cat.id).count > 0 && triOf(op + '/' + cat.id) === 'all'" :disabled="!n(op + '/' + cat.id).count"
                    @change="toggleIds(op + '/' + cat.id)" @click.stop />
                  <span class="node-type-icon"><FolderOpen v-if="isOpen(op + '/' + cat.id)" :size="14" /><Folder v-else :size="14" /></span>
                  <span class="folder-title">{{ t(cat.id === '存量' ? 'lt.tree.cat.legacy' : cat.id === '规划' ? 'lt.tree.cat.planned' : 'lt.tree.cat.survey') }}</span>
                  <span class="folder-count">{{ n(op + '/' + cat.id).count }}</span>
                </h3>

                <template v-if="isOpen(op + '/' + cat.id)">
                  <template v-for="ty in cat.types" :key="ty">
                    <div
                      class="layer-row"
                      :class="{ 'node-highlighted': highlightedKey === `${op}/${cat.id}/${ty}` || (highlightedKey ?? '').startsWith(`${op}/${cat.id}/${ty}/`) }"
                      :style="{ paddingLeft: '52px' }"
                    >
                      <span v-if="cat.id === '勘测'" class="folder-disclose" @click="toggleOpen(`${op}/${cat.id}/${ty}`)">
                        <ChevronDown v-if="isOpen(`${op}/${cat.id}/${ty}`)" :size="14" /><ChevronRight v-else :size="14" />
                      </span>
                      <span v-else class="folder-disclose leaf-spacer" aria-hidden="true" />
                      <input type="checkbox" class="folder-cb-native"
                        :ref="el => setIndet(el, `${op}/${cat.id}/${ty}`)"
                        :checked="n(`${op}/${cat.id}/${ty}`).count > 0 && triOf(`${op}/${cat.id}/${ty}`) === 'all'"
                        :disabled="!n(`${op}/${cat.id}/${ty}`).count"
                        @change="toggleIds(`${op}/${cat.id}/${ty}`)" />
                      <span class="node-type-icon"><SiteShapeIcon :stamp="{ operator: op, category: cat.id, type: ty, target_kind: 'site' }" :size="14" /></span>
                      <span class="folder-title layer-label">{{ ty }}</span>
                      <div class="layer-actions">
                        <button v-if="canImport" class="layer-btn layer-btn-icon" type="button" :disabled="busy" :title="t('lt.btn.import_layer.tip')" @click="openPicker({ operator: op, category: cat.id, type: ty, target_kind: 'site' })">
                          <Download :size="14" :stroke-width="1.8" />
                        </button>
                        <button class="layer-btn layer-btn-icon" type="button" :disabled="busy || !n(`${op}/${cat.id}/${ty}`).count" :title="t('lt.btn.view_features.tip')" @click="viewLayer(`site:${op}:${cat.id}:${ty}`)">
                          <Eye :size="14" :stroke-width="1.8" />
                        </button>
                      </div>
                      <span class="folder-count layer-count">{{ n(`${op}/${cat.id}/${ty}`).count }}</span>
                    </div>

                    <template v-if="isOpen(`${op}/${cat.id}/${ty}`) && cat.id === '勘测'">
                      <div
                        v-for="st in SITE_STATUSES"
                        :key="st"
                        class="style-row"
                        :class="{ 'node-highlighted': highlightedKey === `${op}/${cat.id}/${ty}/${st}` }"
                        :style="{ paddingLeft: '68px' }"
                      >
                        <span class="folder-disclose leaf-spacer" />
                        <input type="checkbox" class="folder-cb-native"
                          :checked="n(`${op}/${cat.id}/${ty}/${st}`).count > 0 && triOf(`${op}/${cat.id}/${ty}/${st}`) === 'all'"
                          :disabled="!n(`${op}/${cat.id}/${ty}/${st}`).count"
                          @change="toggleIds(`${op}/${cat.id}/${ty}/${st}`)" />
                        <span class="node-type-icon"><span class="style-dot" :style="{ background: STATUS_COLOR[st] }" /></span>
                        <span class="style-label">{{ t(`lt.tree.status.${st}`) }}</span>
                        <div class="layer-actions">
                          <button class="layer-btn layer-btn-icon" type="button" :disabled="!n(`${op}/${cat.id}/${ty}/${st}`).count" :title="t('lt.btn.view_features.tip')" @click="viewLayer(`site:${op}:${cat.id}:${ty}:${st}`)">
                            <Eye :size="14" :stroke-width="1.8" />
                          </button>
                        </div>
                        <span class="folder-count">{{ n(`${op}/${cat.id}/${ty}/${st}`).count }}</span>
                      </div>
                      <div
                        v-if="n(`${op}/${cat.id}/${ty}/other`).count"
                        class="style-row"
                        :class="{ 'node-highlighted': highlightedKey === `${op}/${cat.id}/${ty}/other` }"
                        :style="{ paddingLeft: '68px' }"
                      >
                        <span class="folder-disclose leaf-spacer" />
                        <input type="checkbox" class="folder-cb-native"
                          :checked="triOf(`${op}/${cat.id}/${ty}/other`) === 'all'"
                          @change="toggleIds(`${op}/${cat.id}/${ty}/other`)" />
                        <span class="node-type-icon"><span class="style-dot" :style="{ background: STATUS_COLOR[''] }" /></span>
                        <span class="style-label">{{ t("lt.tree.status.other") }}</span>
                        <div class="layer-actions">
                          <button class="layer-btn layer-btn-icon" type="button" @click="viewLayer(`site:${op}:${cat.id}:${ty}:other`)">
                            <Eye :size="14" :stroke-width="1.8" />
                          </button>
                        </div>
                        <span class="folder-count">{{ n(`${op}/${cat.id}/${ty}/other`).count }}</span>
                      </div>
                    </template>
                  </template>
                </template>
              </template>
            </template>
          </template>
        </template>
      </template>

      <template v-if="roadVisible(ctx)">
        <div class="layer-row" :class="{ 'node-highlighted': highlightedKey === 'road' || highlightedKey === 'road/__style__' }" :style="{ paddingLeft: '4px' }">
          <span class="folder-disclose" @click="toggleOpen('road')">
            <ChevronDown v-if="isOpen('road')" :size="14" /><ChevronRight v-else :size="14" />
          </span>
          <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, 'road')"
            :checked="n('road').count > 0 && triOf('road') === 'all'" :disabled="!n('road').count"
            @change="toggleIds('road')" />
          <span class="node-type-icon"><SiteShapeIcon :stamp="{ target_kind: 'road' }" :size="14" /></span>
          <span class="folder-title layer-label">{{ t("lt.tree.road") }}</span>
          <div class="layer-actions">
            <button v-if="canImport" class="layer-btn layer-btn-icon" type="button" :disabled="busy" :title="t('lt.btn.import_layer.tip')" @click="openPicker({ target_kind: 'road' })">
              <Download :size="14" :stroke-width="1.8" />
            </button>
            <button class="layer-btn layer-btn-icon" type="button" :disabled="busy || !n('road').count" :title="t('lt.btn.view_features.tip')" @click="viewLayer('road')">
              <Eye :size="14" :stroke-width="1.8" />
            </button>
          </div>
          <span class="folder-count layer-count">{{ n('road').count }}</span>
        </div>
        <div v-if="isOpen('road')" class="style-row" :class="{ 'node-highlighted': highlightedKey === 'road/__style__' }" :style="{ paddingLeft: '20px' }">
          <span class="folder-disclose leaf-spacer" />
          <input type="checkbox" class="folder-cb-native" :checked="n('road').count > 0 && triOf('road') === 'all'" :disabled="!n('road').count" @change="toggleIds('road')" />
          <span class="node-type-icon"><span class="style-dot" :style="{ background: STATUS_COLOR.road }" /></span>
          <span class="style-label">{{ t("lt.tree.status.road") }}</span>
          <span class="folder-count">{{ n('road').count }}</span>
        </div>
      </template>

      <template v-if="lessorVisible(ctx)">
        <div class="layer-row" :class="{ 'node-highlighted': (highlightedKey ?? '').startsWith('lessor/') }" :style="{ paddingLeft: '4px' }">
          <span class="folder-disclose" @click="toggleOpen('lessor')">
            <ChevronDown v-if="isOpen('lessor')" :size="14" /><ChevronRight v-else :size="14" />
          </span>
          <input type="checkbox" class="folder-cb-native" :ref="el => setIndet(el, 'lessor')"
            :checked="n('lessor').count > 0 && triOf('lessor') === 'all'" :disabled="!n('lessor').count"
            @change="toggleIds('lessor')" />
          <span class="node-type-icon"><SiteShapeIcon :stamp="{ target_kind: 'lessor' }" :size="14" /></span>
          <span class="folder-title layer-label">{{ t("lt.tree.lessor") }}</span>
          <div class="layer-actions">
            <button v-if="canImport" class="layer-btn layer-btn-icon" type="button" :disabled="busy" :title="t('lt.btn.import_layer.tip')" @click="openPicker({ target_kind: 'lessor' })">
              <Download :size="14" :stroke-width="1.8" />
            </button>
            <button class="layer-btn layer-btn-icon" type="button" :disabled="busy || !n('lessor').count" :title="t('lt.btn.view_features.tip')" @click="viewLayer('lessor')">
              <Eye :size="14" :stroke-width="1.8" />
            </button>
          </div>
          <span class="folder-count layer-count">{{ n('lessor').count }}</span>
        </div>
        <template v-if="isOpen('lessor')">
          <div
            v-for="rel in (['Unfriendly', 'Normal'] as const)"
            :key="rel"
            class="style-row"
            :class="{ 'node-highlighted': highlightedKey === 'lessor/' + rel }"
            :style="{ paddingLeft: '20px' }"
          >
            <span class="folder-disclose leaf-spacer" />
            <input type="checkbox" class="folder-cb-native"
              :checked="n('lessor/' + rel).count > 0 && triOf('lessor/' + rel) === 'all'"
              :disabled="!n('lessor/' + rel).count"
              @change="toggleIds('lessor/' + rel)" />
            <span class="node-type-icon"><span class="style-dot" :style="{ background: STATUS_COLOR[rel] }" /></span>
            <span class="style-label">{{ t(`lt.tree.status.${rel.toLowerCase()}`) }}</span>
            <span class="folder-count">{{ n('lessor/' + rel).count }}</span>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>
