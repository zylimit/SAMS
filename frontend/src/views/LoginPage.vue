<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";

const { t } = useI18n();
const store = useAppStore();
const username = ref("");
const password = ref("");
const err = ref("");
const busy = ref(false);
const inputRef = ref<HTMLInputElement | null>(null);

onMounted(() => inputRef.value?.focus());

async function submit() {
  if (busy.value || !username.value || !password.value) return;
  busy.value = true;
  err.value = "";
  try {
    await store.doLogin(username.value, password.value);
  } catch (e) {
    const m = (e as Error).message.toLowerCase();
    if (m.includes("locked")) err.value = t("login.err.locked");
    else if (m.includes("invalid")) err.value = t("login.err.invalid_credentials");
    else err.value = t("login.err.generic");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <form class="login-card" autocomplete="on" @submit.prevent="submit">
      <h1 class="login-title">{{ t("login.title") }}</h1>
      <input
        id="login-username"
        ref="inputRef"
        v-model="username"
        name="username"
        type="text"
        :placeholder="t('login.username')"
        autocomplete="username"
        autocapitalize="off"
        autocorrect="off"
        spellcheck="false"
        :disabled="busy"
        tabindex="1"
      />
      <input
        id="login-password"
        v-model="password"
        name="password"
        type="password"
        :placeholder="t('login.password')"
        autocomplete="current-password"
        :disabled="busy"
        tabindex="2"
      />
      <div v-if="err" class="login-err">{{ err }}</div>
      <button type="submit" class="primary login-btn" tabindex="3" :disabled="busy || !username || !password">
        {{ busy ? t("login.submitting") : t("login.submit") }}
      </button>
    </form>
  </div>
</template>
