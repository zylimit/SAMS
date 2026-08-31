<script setup lang="ts">
import { reactive } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";
import type { CleaningAction } from "../api";

const { t } = useI18n();
const store = useAppStore();
const actions = reactive<Record<string, CleaningAction>>({});

function act(row: { row_id: string; default_action: CleaningAction }): CleaningAction {
  return actions[row.row_id] ?? row.default_action;
}
function setAll(a: CleaningAction) {
  for (const c of store.cleanings as { row_id: string }[]) actions[c.row_id] = a;
}
function next() {
  const decisions = (store.cleanings as { row_id: string; default_action: CleaningAction }[]).map(c => ({
    row_id: c.row_id, action: act(c),
  }));
  void store.goConflicts(decisions);
}
</script>

<template>
  <el-dialog :model-value="store.phase === 'cleaning'" :title="t('cl.step', { file: store.importFile })" width="900px" :close-on-click-modal="false" :close-on-press-escape="false" @close="store.doCancel()">
    <div class="batch">
      <button type="button" @click="setAll('auto_fix')">{{ t("cl.batch.fix") }}</button>
      <button type="button" @click="setAll('keep')">{{ t("cl.batch.keep") }}</button>
      <button type="button" @click="setAll('discard')">{{ t("cl.batch.discard") }}</button>
    </div>
    <el-table :data="store.cleanings" height="360" size="small">
      <el-table-column prop="kind" :label="t('cl.col.type')" width="70" />
      <el-table-column prop="name" :label="t('cl.col.name')" />
      <el-table-column prop="issue" :label="t('cl.col.issue')" width="140" />
      <el-table-column :label="t('cl.col.action')" width="220">
        <template #default="{ row }">
          <el-radio-group :model-value="act(row)" @change="(v: CleaningAction) => actions[row.row_id] = v">
            <el-radio value="auto_fix">{{ t("cl.action.fix") }}</el-radio>
            <el-radio value="keep">{{ t("cl.action.keep") }}</el-radio>
            <el-radio value="discard">{{ t("cl.action.discard") }}</el-radio>
          </el-radio-group>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="store.doCancel()">{{ t("cl.cancel") }}</el-button>
      <el-button type="primary" @click="next">{{ t("cl.next") }}</el-button>
    </template>
  </el-dialog>
</template>
