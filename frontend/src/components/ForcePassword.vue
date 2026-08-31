<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { changePassword } from "../api";
import { useAppStore } from "../stores/app";

const { t } = useI18n();
const store = useAppStore();
const oldp = ref("");
const np = ref("");
const cp = ref("");
const err = ref("");
const busy = ref(false);
const inputRef = ref<HTMLInputElement | null>(null);

onMounted(() => inputRef.value?.focus());

async function submit() {
  if (busy.value || !oldp.value || !np.value || !cp.value) return;
  if (np.value.length < 8) { err.value = t("pw.err.too_short"); return; }
  if (np.value !== cp.value) { err.value = t("pw.err.mismatch"); return; }
  busy.value = true;
  err.value = "";
  try {
    await changePassword(oldp.value, np.value);
    if (store.currentUser) store.currentUser = { ...store.currentUser, must_change_password: false };
  } catch (e) {
    const m = (e as Error).message.toLowerCase();
    err.value = m.includes("invalid") ? t("pw.err.invalid_old") : t("pw.err.generic");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="modal-mask">
    <div class="modal force-pw-modal">
      <div class="modal-header">
        <h2>{{ t("pw.title") }}</h2>
      </div>
      <form @submit.prevent="submit">
        <div class="modal-body force-pw-body">
          <div class="force-pw-hint">{{ t("pw.hint") }}</div>
          <input id="pw-old" ref="inputRef" v-model="oldp" name="current-password" type="password" :placeholder="t('pw.old')" autocomplete="current-password" tabindex="1" :disabled="busy" />
          <input id="pw-new" v-model="np" name="new-password" type="password" :placeholder="t('pw.new')" autocomplete="new-password" tabindex="2" :disabled="busy" />
          <input id="pw-confirm" v-model="cp" name="confirm-password" type="password" :placeholder="t('pw.confirm')" autocomplete="new-password" tabindex="3" :disabled="busy" />
          <div v-if="err" class="login-err">{{ err }}</div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="primary" :disabled="busy || !oldp || !np || !cp">
            {{ busy ? t("pw.submitting") : t("pw.submit") }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
