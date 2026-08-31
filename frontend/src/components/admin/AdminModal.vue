<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  createAdminRole, createAdminUser, deleteAdminRole, exportAuditLog, listAdminRoles, listAdminUsers,
  listAuditLog, listBackups, resetUserPassword, restoreBackup, toggleUserDisabled, updateAdminRole,
} from "../../api";

type AdminUser = {
  id: number;
  username: string;
  role_id: number;
  role_name?: string;
  disabled?: boolean;
  created_at?: string;
};
type AdminRole = {
  id: number;
  name: string;
  user_count?: number;
  is_admin?: boolean;
  perms?: Record<string, boolean>;
  scopes?: string[];
};

const open = defineModel<boolean>({ default: false });
const { t, locale } = useI18n();
const tab = ref("users");
const users = ref<AdminUser[]>([]);
const roles = ref<AdminRole[]>([]);
const audits = ref<Record<string, unknown>[]>([]);
const backups = ref<Record<string, unknown>[]>([]);
const draft = ref<{ username: string; role_id: number; password: string } | null>(null);
const resetTarget = ref<AdminUser | null>(null);
const resetPw = ref("");
const busy = ref(false);
const err = ref("");
const nr = ref({ name: "", perms: { import: false, export: false, edit_delete: false, danger: false }, scopes: ["site", "road", "lessor"] });

async function load() {
  users.value = await listAdminUsers();
  roles.value = await listAdminRoles();
  const page = await listAuditLog({}, 1, 50);
  audits.value = page.items;
  backups.value = await listBackups();
}

function isAdminUser(u: AdminUser) {
  return roles.value.find(r => r.id === u.role_id)?.is_admin === true;
}
function permsSummary(r: AdminRole): string {
  if (r.is_admin) return t("admin.roles.perms_all");
  const keys = ["import", "export", "edit_delete", "danger"] as const;
  const hit = keys.filter(k => r.perms?.[k]).map(k => t(`admin.perm.${k}`));
  return hit.length ? hit.join(" · ") : "—";
}
async function addRole() {
  if (!nr.value.name.trim()) return;
  busy.value = true;
  err.value = "";
  try {
    await createAdminRole(nr.value.name.trim(), nr.value.perms, nr.value.scopes);
    nr.value = { name: "", perms: { import: false, export: false, edit_delete: false, danger: false }, scopes: ["site", "road", "lessor"] };
    await load();
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}
function fmtTs(iso: string | null | undefined) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(locale.value === "zh" ? "zh-CN" : "en-US", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  });
}

function toggleDraft() {
  draft.value = draft.value ? null : {
    username: "",
    role_id: roles.value.find(r => !r.is_admin)?.id ?? roles.value[0]?.id ?? 0,
    password: "",
  };
  err.value = "";
}

async function addUser() {
  if (!draft.value?.username.trim() || !draft.value.password) return;
  busy.value = true;
  err.value = "";
  try {
    await createAdminUser(draft.value.username.trim(), Number(draft.value.role_id), draft.value.password);
    draft.value = null;
    await load();
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function submitReset() {
  if (!resetTarget.value || !resetPw.value) return;
  busy.value = true;
  err.value = "";
  try {
    await resetUserPassword(resetTarget.value.id, resetPw.value);
    resetTarget.value = null;
    resetPw.value = "";
    await load();
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function onToggle(u: AdminUser) {
  if (isAdminUser(u)) return;
  await toggleUserDisabled(u.id);
  await load();
}
</script>

<template>
  <el-dialog v-model="open" :title="t('admin.title')" width="960px" @open="load">
    <el-tabs v-model="tab">
      <el-tab-pane :label="t('admin.tab.users')" name="users">
        <div class="admin-pane">
          <div class="admin-toolbar">
            <button type="button" class="primary" :disabled="busy" @click="toggleDraft">{{ t("admin.users.create") }}</button>
          </div>
          <div v-if="err" class="login-err admin-err">{{ err }}</div>
          <form v-if="draft" class="admin-form" autocomplete="off" @submit.prevent="addUser">
            <input
              v-model="draft.username"
              name="new-username"
              type="text"
              :placeholder="t('admin.users.form.username')"
              autocomplete="off"
              tabindex="1"
              :disabled="busy"
            />
            <select v-model.number="draft.role_id" name="new-role" tabindex="2" :disabled="busy">
              <option v-for="r in roles" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
            <input
              v-model="draft.password"
              name="new-password"
              type="password"
              :placeholder="t('admin.users.form.password')"
              autocomplete="new-password"
              tabindex="3"
              :disabled="busy"
            />
            <button type="submit" class="primary" tabindex="4" :disabled="busy || !draft.username.trim() || !draft.password">{{ t("admin.form.submit") }}</button>
            <button type="button" tabindex="5" :disabled="busy" @click="draft = null">{{ t("admin.form.cancel") }}</button>
          </form>
          <div class="admin-table-wrap">
            <table class="admin-table">
              <thead>
                <tr>
                  <th>{{ t("admin.users.col.username") }}</th>
                  <th>{{ t("admin.users.col.role") }}</th>
                  <th>{{ t("admin.users.col.status") }}</th>
                  <th>{{ t("admin.users.col.created") }}</th>
                  <th>{{ t("admin.users.col.actions") }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!users.length">
                  <td colspan="5" class="audit-empty">{{ t("admin.users.empty") }}</td>
                </tr>
                <tr v-for="u in users" :key="u.id">
                  <td>{{ u.username }}</td>
                  <td>{{ u.role_name ?? "—" }}</td>
                  <td :class="u.disabled ? 'admin-status-off' : 'admin-status-on'">
                    {{ u.disabled ? t("admin.users.status.disabled") : t("admin.users.status.enabled") }}
                  </td>
                  <td class="admin-col-ts">{{ fmtTs(u.created_at) }}</td>
                  <td class="admin-row-actions">
                    <button type="button" :disabled="busy" @click="resetTarget = u; resetPw = ''; err = ''">{{ t("admin.users.reset") }}</button>
                    <button
                      type="button"
                      :class="{ danger: !u.disabled }"
                      :disabled="busy || isAdminUser(u)"
                      :title="isAdminUser(u) ? t('admin.users.disable.admin_tip') : ''"
                      @click="onToggle(u)"
                    >
                      {{ u.disabled ? t("admin.users.enable") : t("admin.users.disable") }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('admin.tab.roles')" name="roles">
        <div class="admin-pane">
          <form class="admin-form" autocomplete="off" @submit.prevent="addRole">
            <input v-model="nr.name" name="new-role-name" type="text" :placeholder="t('admin.roles.form.name')" :disabled="busy" />
            <button type="submit" class="primary" :disabled="busy || !nr.name.trim()">{{ t("admin.roles.create") }}</button>
          </form>
          <div class="admin-table-wrap">
            <table class="admin-table">
              <thead>
                <tr>
                  <th>{{ t("admin.roles.col.name") }}</th>
                  <th>{{ t("admin.roles.col.perms") }}</th>
                  <th>{{ t("admin.roles.col.users") }}</th>
                  <th>{{ t("admin.roles.col.actions") }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!roles.length">
                  <td colspan="4" class="audit-empty">{{ t("admin.roles.empty") }}</td>
                </tr>
                <tr v-for="r in roles" :key="r.id">
                  <td>
                    {{ r.name }}
                    <span v-if="r.is_admin" class="admin-badge">{{ t("admin.roles.builtin") }}</span>
                  </td>
                  <td>{{ permsSummary(r) }}</td>
                  <td>{{ r.user_count ?? 0 }}</td>
                  <td class="admin-row-actions">
                    <button v-if="!r.is_admin" type="button" :disabled="busy" @click="updateAdminRole(r.id, { perms: r.perms, scopes: r.scopes })">{{ t("admin.roles.edit") }}</button>
                    <button v-if="!r.is_admin" type="button" class="danger" :disabled="busy" @click="deleteAdminRole(r.id).then(load)">{{ t("admin.roles.delete") }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('admin.tab.audit')" name="audit">
        <div class="admin-pane">
          <div class="admin-toolbar">
            <button type="button" class="primary" @click="exportAuditLog({})">{{ t("admin.tab.audit") }} Excel</button>
          </div>
          <div class="admin-table-wrap">
            <table class="admin-table">
              <thead>
                <tr>
                  <th>{{ t("audit.col.ts") }}</th>
                  <th>{{ t("audit.col.username") }}</th>
                  <th>{{ t("audit.col.action") }}</th>
                  <th>{{ t("audit.col.result") }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!audits.length">
                  <td colspan="4" class="audit-empty">{{ t("audit.empty") }}</td>
                </tr>
                <tr v-for="(a, i) in audits" :key="i">
                  <td class="admin-col-ts">{{ fmtTs(String(a.ts ?? "")) }}</td>
                  <td>{{ a.username ?? "—" }}</td>
                  <td>{{ a.action ?? "—" }}</td>
                  <td>{{ a.result ?? "—" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('admin.tab.backup')" name="backup">
        <div class="admin-pane">
          <div class="admin-table-wrap">
            <table class="admin-table">
              <thead>
                <tr>
                  <th>{{ t("admin.users.col.created") }}</th>
                  <th>Sites</th>
                  <th>{{ t("admin.roles.col.actions") }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!backups.length">
                  <td colspan="3" class="audit-empty">{{ t("bk.empty") }}</td>
                </tr>
                <tr v-for="b in backups" :key="String(b.id)">
                  <td class="admin-col-ts">{{ fmtTs(String(b.created_at ?? "")) }}</td>
                  <td>{{ b.site_count ?? "—" }}</td>
                  <td class="admin-row-actions">
                    <button type="button" :disabled="busy" @click="restoreBackup(Number(b.id))">{{ t("bk.restore") }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>

  <div v-if="resetTarget" class="modal-mask" @click.self="resetTarget = null">
    <div class="modal confirm-modal" @click.stop>
      <div class="modal-header">
        <h2>{{ t("admin.users.reset.title", { name: resetTarget.username }) }}</h2>
      </div>
      <form autocomplete="off" @submit.prevent="submitReset">
        <div class="modal-body force-pw-body">
          <input
            v-model="resetPw"
            name="reset-password"
            type="password"
            :placeholder="t('admin.users.reset.new')"
            autocomplete="new-password"
            tabindex="1"
            :disabled="busy"
          />
        </div>
        <div class="modal-footer">
          <button type="button" :disabled="busy" @click="resetTarget = null">{{ t("admin.form.cancel") }}</button>
          <button type="submit" class="primary" tabindex="2" :disabled="busy || !resetPw">{{ t("admin.form.submit") }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.row { display: flex; gap: 8px; margin-bottom: 8px; }
.row input, .row select { background: var(--bg-input); color: var(--text); border: 1px solid var(--border); padding: 4px 8px; }
</style>
