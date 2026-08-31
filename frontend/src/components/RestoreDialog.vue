<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { createRestorePoint, deleteRestorePoint, listRestorePoints, rollbackToPoint } from "../api";
import { useAppStore } from "../stores/app";

const open = defineModel<boolean>({ default: false });
const { t } = useI18n();
const store = useAppStore();
const rows = ref<Record<string, unknown>[]>([]);

async function load() { rows.value = await listRestorePoints(); }
onMounted(load);

async function create() { await createRestorePoint(); await load(); }
async function rollback(id: number) {
  await rollbackToPoint(id);
  await store.refresh();
  await store.refreshBaseline();
  open.value = false;
}
async function del(id: number) { await deleteRestorePoint(id); await load(); }
function undoLast() {
  const last = rows.value.find(r => r.reason === "pre_import");
  if (last) void rollback(Number(last.id));
}
</script>

<template>
  <el-dialog v-model="open" :title="t('rp.title')" width="720px" @open="load">
    <div class="row">
      <el-button @click="create">{{ t("rp.create") }}</el-button>
      <el-button @click="undoLast">{{ t("rp.undo") }}</el-button>
    </div>
    <el-table :data="rows" height="360" size="small">
      <el-table-column prop="created_at" :label="t('rp.reason.manual')" />
      <el-table-column prop="reason" />
      <el-table-column prop="site_count" label="site" width="70" />
      <el-table-column prop="road_count" label="road" width="70" />
      <el-table-column>
        <template #default="{ row }">
          <el-button size="small" @click="rollback(row.id)">{{ t("rp.rollback") }}</el-button>
          <el-button size="small" @click="del(row.id)">{{ t("rp.delete") }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
