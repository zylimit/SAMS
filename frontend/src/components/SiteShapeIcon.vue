<script setup lang="ts">
import { computed } from "vue";
import { siteShape } from "../ol/utils";
import type { LayerStamp } from "../api";

const props = defineProps<{ stamp: LayerStamp; size?: number }>();
const size = computed(() => props.size ?? 14);
const kind = computed(() => props.stamp.target_kind);
const site = computed(() => siteShape(props.stamp.type ?? null));
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 16 16" aria-hidden="true">
    <rect v-if="kind === 'road'" x="1" y="7.1" width="14" height="1.8" rx="0.9" fill="currentColor" />
    <rect v-else-if="kind === 'lessor'" x="2.5" y="2.5" width="11" height="11" rx="1.5" fill="currentColor" opacity="0.85" />
    <polygon v-else-if="kind === 'area'" points="8,2.5 14,13.5 2,13.5" fill="currentColor" opacity="0.85" />
    <template v-else>
      <polygon v-if="site.shape === 'triangle'" points="8,2.5 14,13.5 2,13.5"
        :fill="site.filled ? 'currentColor' : 'none'" :stroke="site.filled ? 'none' : 'currentColor'"
        :stroke-width="site.filled ? 0 : 1.6" stroke-linejoin="round" />
      <circle v-else-if="site.shape === 'circle'" cx="8" cy="8" r="5.5"
        :fill="site.filled ? 'currentColor' : 'none'" :stroke="site.filled ? 'none' : 'currentColor'"
        :stroke-width="site.filled ? 0 : 1.6" />
      <rect v-else-if="site.shape === 'square'" x="2.5" y="2.5" width="11" height="11" rx="1"
        :fill="site.filled ? 'currentColor' : 'none'" :stroke="site.filled ? 'none' : 'currentColor'"
        :stroke-width="site.filled ? 0 : 1.6" />
      <polygon v-else points="8,1.8 14.2,8 8,14.2 1.8,8"
        :fill="site.filled ? 'currentColor' : 'none'" :stroke="site.filled ? 'none' : 'currentColor'"
        :stroke-width="site.filled ? 0 : 1.6" stroke-linejoin="round" />
    </template>
  </svg>
</template>
