<script setup lang="ts">
/**
 * Cluster health score with a hover popover detailing node/pod health,
 * issues, problematic pods and recommendations.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AlertOutlined, BugOutlined, DownOutlined, ReloadOutlined, RightOutlined, SafetyOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getInstanceHealthScore } from '@/api/instances'
import { useInstanceDetail } from '../context'

const { t } = useI18n()
const detail = useInstanceDetail()
const expanded = ref(false)

const healthRequest = useRequest(
  (signal) => getInstanceHealthScore(detail.instance.id, { signal }),
  { deps: [() => detail.instance.id] },
)

const health = healthRequest.data
const score = computed(() => health.value?.score ?? null)
const podScore = computed(() => health.value?.podHealth?.score ?? 0)
const podHealthy = computed(() => podScore.value >= 80)

/** Status palette shared with the login skin tokens. */
const healthColor = computed(() => {
  const value = score.value ?? 0
  if (value >= 80) return 'var(--success-color)'
  if (value >= 60) return 'var(--warning-color)'
  return 'var(--error-color)'
})

const podColor = computed(() => (podHealthy.value ? 'var(--success-color)' : 'var(--warning-color)'))

function severityTone(severity?: string): string {
  if (severity === 'critical') return 'status-badge-error'
  if (severity === 'high') return 'status-badge-warning'
  return 'status-badge-neutral'
}

const visibleIssueCount = computed(() =>
  expanded.value ? (health.value?.issues?.length ?? 0) : 3)
const visiblePodCount = computed(() =>
  expanded.value ? (health.value?.podHealth?.problematicPods?.length ?? 0) : 4)
</script>

<template>
  <div class="health-score">
    <span class="health-score-label">{{ t('instance.health_score') }}</span>

    <a-spin v-if="healthRequest.loading && !health" size="small" />
    <a-popover
      v-else-if="score !== null"
      placement="bottomRight"
      trigger="hover"
      :overlay-style="{ maxWidth: '380px' }"
    >
      <template #content>
        <div class="health-pop">
          <div class="health-pop-head">
            <div>
              <div class="health-pop-name">{{ t('instance.health_score') }}</div>
              <div class="health-pop-meta">{{ health?.clusterName }} / {{ health?.namespace }}</div>
            </div>
            <div class="health-pop-score">
              <b :style="{ color: healthColor }">{{ score }}</b>
              <span :class="['status-badge', score! >= 80 ? 'status-badge-success' : score! >= 60 ? 'status-badge-warning' : 'status-badge-error']">
                {{ health?.level }}
              </span>
            </div>
          </div>

          <div class="health-tiles">
            <div class="health-tile">
              <div class="health-tile-head">
                <i :class="['status-dot', health?.nodeHealth?.score === 100 ? 'status-dot-success' : 'status-dot-warning']" />
                {{ t('instance.node_health') }}
              </div>
              <div class="health-tile-value">
                {{ health?.nodeHealth?.readyNodes || 0 }}/{{ health?.nodeHealth?.totalNodes || 0 }}
                <small>Ready</small>
              </div>
              <div class="health-bar">
                <i :style="{ width: `${health?.nodeHealth?.score || 0}%`, background: 'var(--success-color)' }" />
              </div>
            </div>
            <div class="health-tile">
              <div class="health-tile-head">
                <i :class="['status-dot', podHealthy ? 'status-dot-success' : 'status-dot-warning']" />
                {{ t('instance.pod_health') }}
              </div>
              <div class="health-tile-value">
                {{ health?.podHealth?.runningPods || 0 }}/{{ health?.podHealth?.totalPods || 0 }}
                <small>{{ t('instance.restarts') }}: {{ health?.podHealth?.totalRestarts || 0 }}</small>
              </div>
              <div class="health-bar">
                <i :style="{ width: `${podScore}%`, background: podColor }" />
              </div>
            </div>
          </div>

          <div v-if="(health?.issues?.length ?? 0) > 0" class="health-panel health-panel-issues">
            <div class="health-panel-head">
              <AlertOutlined />
              <span>{{ t('instance.issues') }} ({{ health!.issues!.length }})</span>
            </div>
            <div class="health-issues-scroll" :style="{ maxHeight: expanded ? '200px' : '80px' }">
              <div
                v-for="(issue, idx) in health!.issues!.slice(0, visibleIssueCount)"
                :key="idx"
                class="health-issue"
              >
                <span :class="['status-badge', severityTone(issue.severity)]">{{ issue.severity }}</span>
                <span>{{ issue.message }}</span>
              </div>
            </div>
            <div v-if="health!.issues!.length > 3" class="health-more" @click="expanded = !expanded">
              <template v-if="expanded">{{ t('instance.collapse') }}<DownOutlined style="margin-left: 4px" /></template>
              <template v-else>+{{ health!.issues!.length - 3 }} {{ t('instance.more_issues') }}<RightOutlined style="margin-left: 4px" /></template>
            </div>
          </div>

          <div v-if="(health?.podHealth?.problematicPods?.length ?? 0) > 0" class="health-panel health-panel-pods">
            <div class="health-panel-head">
              <BugOutlined />
              <span>{{ t('instance.problematic_pods') }} ({{ health!.podHealth!.problematicPods!.length }})</span>
            </div>
            <div class="health-pod-tags">
              <a-tag
                v-for="(pod, idx) in health!.podHealth!.problematicPods!.slice(0, visiblePodCount)"
                :key="idx"
              >
                {{ pod.name?.substring(0, 20) }}... ({{ pod.restartCount }}{{ t('instance.times') }})
              </a-tag>
            </div>
            <div v-if="health!.podHealth!.problematicPods!.length > 4" class="health-more" @click="expanded = !expanded">
              <template v-if="expanded">{{ t('instance.collapse') }}<DownOutlined style="margin-left: 4px" /></template>
              <template v-else>+{{ health!.podHealth!.problematicPods!.length - 4 }} {{ t('instance.more_pods') }}<RightOutlined style="margin-left: 4px" /></template>
            </div>
          </div>

          <div v-if="(health?.recommendations?.length ?? 0) > 0" class="health-panel health-panel-recs">
            <div class="health-panel-head">
              <SafetyOutlined />
              <span>{{ t('instance.recommendations') }}</span>
            </div>
            <ul>
              <li v-for="(rec, idx) in health!.recommendations!.slice(0, 2)" :key="idx">{{ rec }}</li>
            </ul>
          </div>
        </div>
      </template>

      <div class="health-ring">
        <a-progress
          type="circle"
          :percent="score!"
          :size="80"
          :stroke-color="healthColor"
          trail-color="rgba(148, 163, 184, 0.14)"
        >
          <template #format><span style="font-weight: 650; font-size: 18px">{{ score }}</span></template>
        </a-progress>
        <div class="health-score-level">{{ health?.level }}</div>
      </div>
    </a-popover>
    <span v-else class="health-score-empty">{{ t('instance.health_unavailable') }}</span>

    <a-button
      size="small"
      type="text"
      class="health-score-refresh"
      :loading="healthRequest.loading.value"
      @click="healthRequest.run()"
    >
      <template #icon><ReloadOutlined /></template>
    </a-button>
  </div>
</template>