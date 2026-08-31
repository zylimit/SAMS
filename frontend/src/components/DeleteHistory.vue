<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { fetchDeleteHistory, undoDelete } from "../api";
import { useAppStore } from "../stores/app";

const open = defineModel<boolean>({ default: false });
const { t } = useI18n();
const store = useAppStore();
const rows = ref<Record<string, unknown>[]>([]);
async function load() { rows.value = await fetchDeleteHistory(); }
async function undo(id: number) {
  const r = await undoDelete(id);
  store.log("info", t("log.undo_delete_ok", { restored: r.restored, requested: r.requested }));
  await store.refresh();
  await load();
}
</script>

<template>
  <el-dialog v-model="open" :title="t('tb.delhist.label')" width="720px" @open="load">
    <el-table :data="rows" height="360" size="small">
      <el-table-column prop="deleted_at" />
      <el-table-column prop="count" width="80" />
      <el-table-column prop="layer" />
      <el-table-column>
        <template #default="{ row }">
          <el-button v-if="!row.undone" size="small" @click="undo(row.undo_id)">{{ t("rp.undo") }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
