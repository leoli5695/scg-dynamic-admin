<script setup lang="ts">
/**
 * Feature authorization matrix grouped by edition on the Vue-native layer:
 * a native segmented group switch plus a grid of matte feature tiles. Clicking
 * a tile emits `select` so the page can open the detail drawer.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircleOutlined, CrownOutlined, LockOutlined } from '@/components/icons'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import {
  EDITION_CONFIG,
  type LicenseFeatureDef,
} from '../constants/featureCatalog'

const props = defineProps<{ features: LicenseFeatureDef[] }>()
const emit = defineEmits<{ (e: 'select', feature: LicenseFeatureDef): void }>()

const { t } = useI18n()
const groupKey = ref<'basic' | 'advanced' | 'enterprise'>('basic')

const groups = computed(() => [
  { key: 'basic' as const, label: t('licenseUI.basic_features_free'), icon: CheckCircleOutlined, items: props.features.filter((f) => f.requiredEdition === 'BASIC') },
  { key: 'advanced' as const, label: t('licenseUI.advanced_features'), icon: CrownOutlined, items: props.features.filter((f) => f.requiredEdition === 'ADVANCED') },
  { key: 'enterprise' as const, label: t('licenseUI.enterprise_features'), icon: CrownOutlined, items: props.features.filter((f) => f.requiredEdition === 'ENTERPRISE') },
])

const groupOptions = computed<VnSegmentOption<'basic' | 'advanced' | 'enterprise'>[]>(() =>
  groups.value.map((g) => ({ key: g.key, label: g.label, count: g.items.length, icon: g.icon })))

const activeItems = computed(() => groups.value.find((g) => g.key === groupKey.value)?.items ?? [])

const enabledCount = computed(() => props.features.filter((feature) => feature.enabled).length)

function isFree(feature: LicenseFeatureDef): boolean {
  return feature.requiredEdition === 'BASIC' || feature.enabled
}

function statusLabel(feature: LicenseFeatureDef): string {
  if (isFree(feature)) return t(feature.requiredEdition === 'BASIC' ? 'licenseUI.free' : 'licenseUI.authorized')
  return t(EDITION_CONFIG[feature.requiredEdition].labelKey)
}

function statusTone(feature: LicenseFeatureDef): 'ok' | 'info' | 'warn' {
  if (isFree(feature)) return 'ok'
  return feature.requiredEdition === 'ADVANCED' ? 'info' : 'warn'
}

function statusIcon(feature: LicenseFeatureDef) {
  return isFree(feature) ? CheckCircleOutlined : LockOutlined
}
</script>

<template>
  <section class="vn-panel">
    <header class="vn-panel-head">
      <span class="vnp-panel-head">{{ t('licenseUI.feature_matrix') }}</span>
      <span class="vnp-count">{{ t('licenseUI.feature_count', { enabled: enabledCount, total: features.length }) }}</span>
    </header>
    <div class="vn-panel-body">
      <VnSegmented v-model="groupKey" :options="groupOptions" :label="t('licenseUI.feature_matrix')" class="vn-license-matrix-seg" />
      <div class="vn-license-feature-grid">
        <button
          v-for="feature in activeItems"
          :key="feature.name"
          type="button"
          :class="['vn-panel', 'vn-license-feature-item', { 'is-enabled': feature.enabled }]"
          @click="emit('select', feature)"
        >
          <span :class="['vn-license-feature-icon', isFree(feature) ? 'vn-ink-ok' : 'vn-ink-idle']" aria-hidden="true">
            <component :is="feature.icon" />
          </span>
          <span class="vn-license-feature-content">
            <span class="vn-license-feature-head">
              <span class="vn-license-feature-name">{{ t(feature.displayNameKey) }}</span>
              <VnBadge :tone="statusTone(feature)">
                <component :is="statusIcon(feature)" />
                {{ statusLabel(feature) }}
              </VnBadge>
            </span>
            <span class="vn-license-feature-desc">{{ t(feature.descriptionKey) }}</span>
          </span>
        </button>
      </div>
    </div>
  </section>
</template>