<script setup lang="ts">
/**
 * Placeholder for feature modules not yet ported. Shows the target route so the
 * shell stays navigable during the staged rewrite. Replaced module-by-module in
 * later stages.
 */
import { useRoute } from 'vue-router'
import { Card, Empty, Tag } from 'ant-design-vue'

const route = useRoute()
const title = (route.meta.title as string) || route.path
</script>

<template>
  <div class="page-container">
    <Card :bordered="false">
      <Empty :image="Empty.PRESENTED_IMAGE_SIMPLE">
        <template #description>
          <div style="display: flex; flex-direction: column; gap: 8px; align-items: center">
            <span style="font-size: 15px; color: var(--text-secondary)">{{ title }}</span>
            <Tag color="blue">该模块将在后续 Stage 移植</Tag>
          </div>
        </template>
      </Empty>
    </Card>
  </div>
</template>
