<script setup lang="ts">
/**
 * Cluster health score with a hover popover detailing node/pod health,
 * issues, problematic pods and recommendations.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AlertOutlined, BugOutlined, DownOutlined, ReloadOutlined, RightOutlined, SafetyOutlined,
} from '@ant-design/icons-vue'
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

function getHealthColor(s: number): string {
  if (s >= 90) return '#52c41a'
  if (s >= 80) return '#73d13d'
  if (s >= 60) return '#faad14'
  if (s >= 40) return '#fa8c16'
  return '#f5222d'
}
function getHealthTagColor(s: number): string {
  if (s >= 90) return 'success'
  if (s >= 80) return 'green'
  if (s >= 60) return 'warning'
  if (s >= 40) return 'orange'
  return 'error'
}
</script>

<template>
  <div style="text-align: center">
    <div style="margin-bottom: 8px; color: var(--text-muted); font-size: 12px">
      {{ t('instance.health_score') || '集群健康评分' }}
    </div>

    <a-spin v-if="healthRequest.loading && !health" size="small" />
    <a-popover
      v-else-if="score !== null"
      placement="bottom"
      trigger="hover"
      :overlay-style="{ maxWidth: '380px' }"
    >
      <template #content>
        <div style="width: 340px">
          <!-- Header with score -->
          <div style="display: flex; align-items: center; justify-content: space-between; padding: '12px 0'; border-bottom: 1px solid rgba(148,163,184,0.15); margin-bottom: 12px">
            <div>
              <div style="font-size: 13px; font-weight: 600; color: var(--text-primary)">{{ t('instance.health_score') || '集群健康评分' }}</div>
              <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">{{ health?.clusterName }} / {{ health?.namespace }}</div>
            </div>
            <div style="text-align: right">
              <div style="font-size: 24px; font-weight: bold; color: getHealthColor(score!)">{{ score }}</div>
              <a-tag :color="getHealthTagColor(score!)" style="margin-top: 4px">{{ health?.level }}</a-tag>
            </div>
          </div>

          <!-- Node / Pod health grid -->
          <a-row :gutter="[12, 12]" style="margin-bottom: 12px">
            <a-col :span="12">
              <div style="background: rgba(34,197,94,0.08); border-radius: 8px; padding: 10px 12px; border: 1px solid rgba(34,197,94,0.3)">
                <div style="display: flex; align-items: center; margin-bottom: 8px">
                  <a-badge :status="health?.nodeHealth?.score === 100 ? 'success' : 'warning'" />
                  <span style="font-size: 12px; font-weight: 500; margin-left: 6px">{{ t('instance.node_health') || '节点' }}</span>
                </div>
                <div style="font-size: 16px; font-weight: bold; color: #52c41a">{{ health?.nodeHealth?.readyNodes || 0 }}/{{ health?.nodeHealth?.totalNodes || 0 }}</div>
                <a-progress :percent="health?.nodeHealth?.score || 0" size="small" :show-info="false" stroke-color="#52c41a" trail-color="rgba(34,197,94,0.2)" />
              </div>
            </a-col>
            <a-col :span="12">
              <div :style="{ background: podScore >= 80 ? 'rgba(34,197,94,0.08)' : 'rgba(234,179,8,0.08)', borderRadius: '8px', padding: '10px 12px', border: `1px solid ${podScore >= 80 ? 'rgba(34,197,94,0.3)' : 'rgba(234,179,8,0.3)'}` }">
                <div style="display: flex; align-items: center; margin-bottom: 8px">
                  <a-badge :status="podScore >= 80 ? 'success' : 'warning'" />
                  <span style="font-size: 12px; font-weight: 500; margin-left: 6px">{{ t('instance.pod_health') || 'Pod' }}</span>
                </div>
                <div :style="{ fontSize: '16px', fontWeight: 'bold', color: podScore >= 80 ? '#52c41a' : '#faad14' }">{{ health?.podHealth?.runningPods || 0 }}/{{ health?.podHealth?.totalPods || 0 }}</div>
                <div style="font-size: 11px; color: var(--text-muted); margin-top: 4px">{{ t('instance.restarts') || '重启' }}: {{ health?.podHealth?.totalRestarts || 0 }}</div>
                <a-progress :percent="health?.podHealth?.score || 0" size="small" :show-info="false" :stroke-color="podScore >= 80 ? '#52c41a' : '#faad14'" :trail-color="podScore >= 80 ? 'rgba(34,197,94,0.2)' : 'rgba(234,179,8,0.2)'" />
              </div>
            </a-col>
          </a-row>

          <!-- Issues -->
          <div v-if="(health?.issues?.length ?? 0) > 0" style="margin-bottom: 12px; background: rgba(239,68,68,0.08); border-radius: 8px; padding: 10px 12px; border: 1px solid rgba(239,68,68,0.3)">
            <div style="display: flex; align-items: center; margin-bottom: 8px">
              <AlertOutlined style="color: #ff4d4f; font-size: 14px" />
              <span style="font-weight: 500; margin-left: 8px; color: #ff4d4f">{{ t('instance.issues') || '问题' }} ({{ health!.issues!.length }})</span>
            </div>
            <div :style="{ maxHeight: expanded ? '200px' : '80px', overflowY: 'auto' }">
              <div
                v-for="(issue, idx) in health!.issues!.slice(0, expanded ? health!.issues!.length : 3)"
                :key="idx"
                style="display: flex; align-items: center; margin-bottom: 4px; padding: 4px 8px; background: rgba(148,163,184,0.08); border-radius: 4px; font-size: 11px"
              >
                <a-tag :color="issue.severity === 'critical' ? 'error' : issue.severity === 'high' ? 'warning' : 'default'" style="font-size: 10px; margin-right: 8px">{{ issue.severity }}</a-tag>
                <span style="color: var(--text-secondary); flex: 1">{{ issue.message }}</span>
              </div>
            </div>
            <div v-if="health!.issues!.length > 3" style="text-align: center; color: #1890ff; font-size: 11px; padding: 4px; cursor: pointer; margin-top: 4px" @click="expanded = !expanded">
              <template v-if="expanded">{{ t('instance.collapse') || '收起' }} <DownOutlined style="margin-left: 4px" /></template>
              <template v-else>+{{ health!.issues!.length - 3 }} {{ t('instance.more_issues') || '更多' }} <RightOutlined style="margin-left: 4px" /></template>
            </div>
          </div>

          <!-- Problematic pods -->
          <div v-if="(health?.podHealth?.problematicPods?.length ?? 0) > 0" style="margin-bottom: 12px; background: rgba(148,163,184,0.06); border-radius: 8px; padding: 10px 12px">
            <div style="display: flex; align-items: center; margin-bottom: 8px">
              <BugOutlined style="color: #fa8c16; font-size: 14px" />
              <span style="font-weight: 500; margin-left: 8px; color: var(--text-secondary)">{{ t('instance.problematic_pods') || '问题Pod' }} ({{ health!.podHealth!.problematicPods!.length }})</span>
            </div>
            <div style="display: flex; flex-wrap: wrap; gap: 6px">
              <a-tag v-for="(pod, idx) in health!.podHealth!.problematicPods!.slice(0, expanded ? health!.podHealth!.problematicPods!.length : 4)" :key="idx" style="font-size: 11px; border-radius: 4px">
                {{ pod.name?.substring(0, 20) }}... ({{ pod.restartCount }}{{ t('instance.times') || '次' }})
              </a-tag>
            </div>
            <div v-if="health!.podHealth!.problematicPods!.length > 4" style="text-align: center; color: #1890ff; font-size: 11px; padding: 4px; cursor: pointer; margin-top: 6px" @click="expanded = !expanded">
              <template v-if="expanded">{{ t('instance.collapse') || '收起' }} <DownOutlined style="margin-left: 4px" /></template>
              <template v-else>+{{ health!.podHealth!.problematicPods!.length - 4 }} {{ t('instance.more_pods') || '还有' }} <RightOutlined style="margin-left: 4px" /></template>
            </div>
          </div>

          <!-- Recommendations -->
          <div v-if="(health?.recommendations?.length ?? 0) > 0" style="background: rgba(59,130,246,0.08); border-radius: 8px; padding: 10px 12px; border: 1px solid rgba(59,130,246,0.3)">
            <div style="display: flex; align-items: center; margin-bottom: 8px">
              <SafetyOutlined style="color: #1890ff; font-size: 14px" />
              <span style="font-weight: 500; margin-left: 8px; color: #1890ff">{{ t('instance.recommendations') || '建议' }}</span>
            </div>
            <ul style="margin: 0; padding-left: 18px; font-size: 11px; color: var(--text-secondary)">
              <li v-for="(rec, idx) in health!.recommendations!.slice(0, 2)" :key="idx" style="margin-bottom: 2px">{{ rec }}</li>
            </ul>
          </div>
        </div>
      </template>

      <div style="cursor: pointer">
        <a-progress type="circle" :percent="score" :size="80" :stroke-color="getHealthColor(score!)">
          <template #format><span style="font-weight: bold; font-size: 18px">{{ score }}</span></template>
        </a-progress>
        <div style="margin-top: 8px; font-size: 12px">
          <a-tag :color="getHealthTagColor(score!)">{{ health?.level }}</a-tag>
        </div>
      </div>
    </a-popover>
    <span v-else class="ant-typography" style="color: var(--text-secondary)">{{ t('instance.health_unavailable') || '暂无数据' }}</span>

    <a-button size="small" type="link" :loading="healthRequest.loading" style="margin-top: 8px" @click="healthRequest.run()">
      <template #icon><ReloadOutlined /></template>
    </a-button>
  </div>
</template>
