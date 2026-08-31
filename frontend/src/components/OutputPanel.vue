<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";

const open = defineModel<boolean>({ default: false });
const { t } = useI18n();
const store = useAppStore();

const PHASE_KEY: Record<string, string> = {
  idle: "phase.idle",
  loading: "phase.loading",
  uploading: "phase.uploading",
  cleaning: "phase.cleaning",
  conflicts: "phase.conflicts",
  committing: "phase.committing",
  exporting: "phase.exporting",
};
const PHASE_BUSY: Record<string, boolean> = {
  idle: false, loading: true, uploading: true, cleaning: false,
  conflicts: false, committing: true, exporting: true,
};

const latest = computed(() => store.logs.at(-1));
const showBar = computed(() => store.phase !== "idle");
const dotClass = computed(() => store.dbOk === null ? "dot" : store.dbOk ? "dot ok" : "dot err");
const dotLabel = computed(() => store.dbOk === null ? t("op.db.checking") : store.dbOk ? t("op.db.ok") : t("op.db.error"));
const progress = computed(() => store.phase === "loading" ? store.loadProgress : store.commitProgress);
const determinate = computed(() => !!progress.value && progress.value.total > 0 && (store.phase === "committing" || store.phase === "loading"));
</script>

<template>
  <div class="output">
    <div class="bar" @click="open = !open">
      <span>{{ open ? "▾" : "▸" }}</span>
      <span class="phase-label">
        {{ showBar ? t(PHASE_KEY[store.phase] ?? "phase.idle") : (latest ? `${latest.ts} ${latest.msg}` : t("phase.idle")) }}
      </span>
      <div
        v-if="showBar"
        class="progress"
        :class="determinate ? 'determinate' : (PHASE_BUSY[store.phase] ? 'indeterminate' : 'paused')"
      >
        <div class="bar-inner" :style="determinate && progress ? { width: `${progress.pct}%` } : undefined" />
      </div>
      <span v-if="determinate && progress" class="commit-progress-text">
        {{ t(store.phase === 'loading' ? 'op.load_progress' : 'op.commit_progress', progress) }}
      </span>
      <span class="app-version" @click.stop :title="`v${__APP_VERSION__} · ${__BUILD_TIME__}`">
        v{{ __APP_VERSION__ }} · {{ __BUILD_TIME__ }}
      </span>
      <div class="status">
        <span :class="dotClass" />
        <span>{{ dotLabel }}</span>
      </div>
    </div>
    <div v-if="open" class="body">
      <div class="body-head">
        <span class="muted">{{ t("op.logs.header") }}</span>
        <button class="clear-btn" type="button" :disabled="!store.logs.length" :title="t('op.logs.clear')" @click.stop="store.logs = []">
          {{ t("op.logs.clear") }}
        </button>
      </div>
      <div v-if="!store.logs.length" class="row muted">{{ t("op.logs.empty") }}</div>
      <div v-for="(l, i) in store.logs" :key="i" class="row" :class="l.level">[{{ l.ts }}] {{ l.msg }}</div>
    </div>
  </div>
</template>
