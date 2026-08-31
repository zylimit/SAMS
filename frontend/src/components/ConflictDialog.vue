<script setup lang="ts">
import { reactive } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";
import type { Decision } from "../api";

const { t } = useI18n();
const store = useAppStore();
const actions = reactive<Record<string, Decision>>({});
function act(row: { key: string }): Decision { return actions[row.key] ?? "ignore"; }
function setAll(a: Decision) { for (const c of store.conflicts as { key: string }[]) actions[c.key] = a; }
function confirm() {
  const decisions = (store.conflicts as { key: string }[]).map(c => ({ key: c.key, action: act(c) }));
  void store.doCommit(decisions);
}
</script>

<template>
  <el-dialog :model-value="store.phase === 'conflicts' || store.phase === 'committing'" :title="t('co.title', { count: store.conflicts.length })" width="920px" :close-on-click-modal="false" :close-on-press-escape="store.phase !== 'committing'">
    <div class="batch">
      <button type="button" @click="setAll('overwrite')">{{ t("co.batch.overwrite") }}</button>
      <button type="button" @click="setAll('ignore')">{{ t("co.batch.ignore") }}</button>
    </div>
    <el-table :data="store.conflicts" height="360" size="small">
      <el-table-column prop="kind" :label="t('co.col.type')" width="70" />
      <el-table-column prop="name" :label="t('co.col.name')" />
      <el-table-column prop="source_file" :label="t('co.col.file')" />
      <el-table-column :label="t('co.col.action')" width="220">
        <template #default="{ row }">
          <el-radio-group :model-value="act(row)" @change="(v: Decision) => actions[row.key] = v">
            <el-radio value="overwrite">{{ t("co.action.overwrite") }}</el-radio>
            <el-radio value="ignore">{{ t("co.action.ignore") }}</el-radio>
          </el-radio-group>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button :disabled="store.phase === 'committing'" @click="store.goBackCleaning()">{{ t("co.back") }}</el-button>
      <el-button :disabled="store.phase === 'committing'" @click="store.doCancel(true)">{{ t("co.cancel") }}</el-button>
      <el-button type="primary" :loading="store.phase === 'committing'" @click="confirm">{{ t("co.confirm", { ov: (store.conflicts as {key:string}[]).filter(c => act(c)==='overwrite').length, ig: (store.conflicts as {key:string}[]).filter(c => act(c)==='ignore').length }) }}</el-button>
    </template>
  </el-dialog>
</template>
