<script setup lang="ts">
/**
 * Instance detail console layout — header with lifecycle actions, URL-driven
 * tabs, router-view for tab content. Tab state lives in the URL
 * (/instances/:instanceId/:tab) so refresh keeps it. Provides the instance
 * context to all tabs.
 */
import { computed, provide, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AlertOutlined, ApartmentOutlined, AppstoreOutlined, ArrowLeftOutlined,
  BellOutlined, BugOutlined, CloudServerOutlined, DashboardOutlined,
  DatabaseOutlined, DeploymentUnitOutlined, FileTextOutlined, FilterOutlined,
  HistoryOutlined, HomeOutlined, KeyOutlined, LineChartOutlined, LinkOutlined,
  LockOutlined, MedicineBoxOutlined, PauseCircleOutlined, PlayCircleOutlined,
  PlusSquareOutlined, ReloadOutlined, RobotOutlined, RocketOutlined,
  SafetyOutlined, StopOutlined, ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import {
  getInstanceByInstanceId, getInstanceConfig, startInstance, stopInstance,
} from '@/api/instances'
import { ApiError } from '@/api/http'
import type { GatewayInstance } from '@/types/instance'
import { getStatusColorByName, getStatusTextByName } from '@/utils/instance'
import { message } from '@/utils/feedback'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import UserMenu from '@/components/UserMenu.vue'
import { createInstanceDetailContext, INSTANCE_DETAIL_KEY } from './context'
import ScaleModal from './ScaleModal.vue'
import './instance-detail.css'

/** Tab key → icon mapping (order defines tab order). */
const TAB_ICONS = {
  overview: CloudServerOutlined,
  services: AppstoreOutlined,
  routes: DeploymentUnitOutlined,
  plugins: RocketOutlined,
  plugin_bindings: LinkOutlined,
  strategies: SafetyOutlined,
  authentication: LockOutlined,
  token_revocation: StopOutlined,
  certificates: KeyOutlined,
  trace: LineChartOutlined,
  monitor: DashboardOutlined,
  alerts: BellOutlined,
  access_log: FileTextOutlined,
  audit_logs: HistoryOutlined,
  diagnostic: MedicineBoxOutlined,
  topology: ApartmentOutlined,
  filter_chain: FilterOutlined,
  request_replay: BugOutlined,
  copilot: RobotOutlined,
  stress_test: ThunderboltOutlined,
  events: AlertOutlined,
  resources: DashboardOutlined,
  middleware: DatabaseOutlined,
} as const

type TabKey = keyof typeof TAB_ICONS
const TAB_ORDER = Object.keys(TAB_ICONS) as TabKey[]

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const scaleModalOpen = ref(false)

const instanceId = computed(() => route.params.instanceId as string)

const instanceRequest = useRequest(
  (signal) => getInstanceByInstanceId(instanceId.value ?? '', { signal }),
  { deps: [instanceId] },
)
const configRequest = useRequest((signal) => getInstanceConfig({ signal }))

// Provide the shared context; fill it as data loads.
const ctx = createInstanceDetailContext(() => instanceRequest.run())
provide(INSTANCE_DETAIL_KEY, ctx)
watch(instanceRequest.data, (v) => { if (v) ctx.instance = v }, { immediate: true })
watch(configRequest.data, (v) => { ctx.globalNacosAddr = v?.nacosServerAddr || '127.0.0.1:8848' }, { immediate: true })

const instance = instanceRequest.data

// Non-null view for the rendered branch (the template guards on `instance` first).
const inst = computed<GatewayInstance>(() => instanceRequest.data.value as GatewayInstance)

const activeTab = computed<TabKey>(() => {
  const parts = route.path.split('/')
  const candidate = parts[3]
  return candidate && (TAB_ORDER as string[]).includes(candidate) ? (candidate as TabKey) : 'overview'
})

function tabI18nKey(key: TabKey): string {
  return key === 'overview' ? 'instance.overview' : `menu.${key}`
}

function handleTabChange(key: string) {
  router.push(key === 'overview' ? `/instances/${instanceId.value}` : `/instances/${instanceId.value}/${key}`)
}

async function runLifecycle(action: () => Promise<unknown>, successKey: string, failKey: string) {
  try {
    await action()
    message.success(t(successKey))
    instanceRequest.run()
  } catch (error) {
    const msg = error instanceof ApiError && error.message ? error.message : t(failKey)
    message.error(msg)
  }
}

const statusText = computed(() => getStatusTextByName(instance.value?.status ?? '', t))
const statusColor = computed(() => getStatusColorByName(instance.value?.status ?? ''))
</script>

<template>
  <div v-if="instanceRequest.loading && !instance" style="display: flex; justify-content: center; align-items: center; min-height: 400px">
    <a-spin size="large" />
  </div>

  <div
    v-else-if="!instance"
    style="display: flex; justify-content: center; align-items: center; min-height: 400px; padding: 24px"
  >
    <a-result status="warning" :title="t('instance.not_found') || '实例不存在'" sub-title="该实例可能已被删除或您没有访问权限">
      <template #extra>
        <a-button type="primary" @click="instanceRequest.run()">
          <template #icon><ReloadOutlined /></template>刷新页面
        </a-button>
        <a-button @click="router.push('/instances')">
          <template #icon><HomeOutlined /></template>返回列表
        </a-button>
      </template>
    </a-result>
  </div>

  <div v-else class="instance-detail-page">
    <!-- Header -->
    <div class="instance-detail-header">
      <div class="instance-detail-header-left">
        <a-button type="text" class="instance-detail-back-btn" @click="router.push('/instances')">
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>
        <CloudServerOutlined class="instance-detail-icon" />
        <h4 class="instance-detail-title ant-typography">{{ inst.instanceName }}</h4>
        <a-tag class="instance-detail-status-tag" :color="statusColor">{{ statusText }}</a-tag>
      </div>

      <div class="instance-detail-header-right">
        <template v-if="inst.status === 'Running'">
          <a-button @click="scaleModalOpen = true">
            <template #icon><PlusSquareOutlined /></template>{{ t('instance.scale') }}
          </a-button>
          <a-button @click="runLifecycle(() => stopInstance(inst.id), 'instance.stop_success', 'instance.stop_failed')">
            <template #icon><PauseCircleOutlined /></template>{{ t('instance.stop') }}
          </a-button>
        </template>
        <a-button
          v-if="inst.status === 'Stopped'"
          type="primary"
          @click="runLifecycle(() => startInstance(inst.id), 'instance.start_success', 'instance.start_failed')"
        >
          <template #icon><PlayCircleOutlined /></template>{{ t('instance.start') }}
        </a-button>
        <a-tooltip :title="t('common.refresh')">
          <a-button @click="instanceRequest.run()">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </a-tooltip>
        <LanguageSwitcher />
        <span style="margin-left: 12px"><UserMenu /></span>
      </div>
    </div>

    <!-- Tabs (URL-driven) -->
    <a-tabs :active-key="activeTab" class="instance-detail-tabs" @change="handleTabChange">
      <a-tab-pane v-for="key in TAB_ORDER" :key="key">
        <template #tab>
          <span>
            <component :is="TAB_ICONS[key]" />
            {{ t(tabI18nKey(key)) }}
          </span>
        </template>
      </a-tab-pane>
    </a-tabs>

    <!-- Tab content via router -->
    <div class="instance-detail-content">
      <RouterView />
    </div>

    <ScaleModal
      v-if="scaleModalOpen"
      :instance="inst"
      @close="scaleModalOpen = false"
      @scaled="instanceRequest.run()"
    />
  </div>
</template>
