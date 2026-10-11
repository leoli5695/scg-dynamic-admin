<script setup lang="ts">
/**
 * Events tab — Kubernetes events for the instance pods.
 * Uses GET /api/instances/{id}/events (sinceSeconds=3600, limit=50).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import { getInstanceEvents } from '@/api/instances'
import { useInstanceDetail } from '../context'

const { t } = useI18n()
const detail = useInstanceDetail()

const eventsRequest = useRequest(
  (signal) => getInstanceEvents(detail.instance.id, { sinceSeconds: 3600, limit: 50 }, { signal }),
  { deps: [() => detail.instance.id] },
)

const events = computed(() => eventsRequest.data.value?.events ?? [])
const total = computed(() => eventsRequest.data.value?.total ?? 0)
const warningCount = computed(() => eventsRequest.data.value?.warningCount ?? 0)
const normalCount = computed(() => eventsRequest.data.value?.normalCount ?? 0)
</script>

<template>
  <a-card :bordered="false">
    <div :style="{ marginBottom: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }">
      <a-space>
        <a-badge :status="warningCount > 0 ? 'error' : 'success'" :text="`${warningCount} Warning`" />
        <a-badge status="processing" :text="`${normalCount} Normal`" />
        <span class="ant-typography" style="color: var(--text-secondary)">Total: {{ total }}</span>
      </a-space>
      <a-button :loading="eventsRequest.loading" @click="eventsRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') || '刷新' }}
      </a-button>
    </div>

    <div v-if="eventsRequest.loading && events.length === 0" :style="{ display: 'flex', justifyContent: 'center', padding: '40px' }">
      <a-spin />
    </div>
    <div v-else-if="events.length === 0" :style="{ textAlign: 'center', padding: '40px' }">
      <span class="ant-typography" style="color: var(--text-secondary)">{{ t('events.no_events') || '暂无事件数据' }}</span>
    </div>
    <div v-else :style="{ maxHeight: '500px', overflowY: 'auto' }">
      <a-card
        v-for="(event, idx) in events"
        :key="idx"
        size="small"
        :bordered="false"
        :class="event.type === 'Warning' ? 'event-card-warning' : 'event-card-normal'"
        :style="{ marginBottom: '8px' }"
      >
        <a-row :gutter="16">
          <a-col :span="2">
            <a-tag :color="event.type === 'Warning' ? 'error' : 'success'">{{ event.type }}</a-tag>
          </a-col>
          <a-col :span="3">
            <a-tag color="blue">{{ event.reason }}</a-tag>
          </a-col>
          <a-col :span="14">
            <span class="ant-typography">{{ event.message }}</span>
          </a-col>
          <a-col :span="5">
            <a-space direction="vertical" size="small">
              <span class="ant-typography" style="color: var(--text-secondary); font-size: 12px">
                <ClockCircleOutlined /> {{ event.objectName }}
              </span>
              <span class="ant-typography" style="color: var(--text-secondary); font-size: 12px">Count: {{ event.count }}</span>
            </a-space>
          </a-col>
        </a-row>
      </a-card>
    </div>
  </a-card>
</template>
