<script setup lang="ts">
/**
 * Events tab — Kubernetes events for the instance pods.
 * Uses GET /api/instances/{id}/events (sinceSeconds=3600, limit=50).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ReloadOutlined } from '@/components/icons'
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

function eventStamp(event: { lastTimestamp?: string | null; firstTimestamp?: string | null }): string {
  return event.lastTimestamp || event.firstTimestamp || '-'
}
</script>

<template>
  <div class="card-base events-panel">
    <div class="panel-head">
      <div class="panel-head-left">
        <span class="panel-title">{{ t('menu.events') }}</span>
        <span class="status-badge status-badge-error">{{ warningCount }} Warning</span>
        <span class="status-badge status-badge-success">{{ normalCount }} Normal</span>
        <span class="panel-count">{{ t('common.total') }}: {{ total }}</span>
      </div>
      <a-button :loading="eventsRequest.loading.value" @click="eventsRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </div>

    <div v-if="eventsRequest.loading && events.length === 0" class="events-state">
      <a-spin />
    </div>
    <div v-else-if="events.length === 0" class="events-state">
      {{ t('events.no_events') }}
    </div>
    <div v-else class="event-list events-scroll">
      <div v-for="(event, idx) in events" :key="idx" class="event-row">
        <span :class="['status-badge', event.type === 'Warning' ? 'status-badge-error' : 'status-badge-success']">
          {{ event.type }}
        </span>
        <span class="event-reason" :title="event.reason">{{ event.reason }}</span>
        <span class="event-message">{{ event.message }}</span>
        <span class="event-meta">
          <span class="mono" :title="event.objectName">{{ event.objectName }}</span>
          <span>{{ eventStamp(event) }}</span>
          <span>× {{ event.count }}</span>
        </span>
      </div>
    </div>
  </div>
</template>