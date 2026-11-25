<script setup lang="ts">
/**
 * Feature authorization matrix grouped by edition, rendered as login-style cards.
 * Clicking a card emits `select` so the page can open the detail drawer.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircleOutlined, CrownOutlined, LockOutlined } from '@/components/icons'
import {
  EDITION_CONFIG,
  EDITION_TONE,
  type LicenseFeatureDef,
} from '../constants/featureCatalog'

const props = defineProps<{ features: LicenseFeatureDef[] }>()
const emit = defineEmits<{ (e: 'select', feature: LicenseFeatureDef): void }>()

const { t } = useI18n()

const groups = computed(() => [
  {
    key: 'basic',
    label: t('licenseUI.basic_features_free'),
    icon: CheckCircleOutlined,
    tone: 'license-tone-success',
    items: props.features.filter((feature) => feature.requiredEdition === 'BASIC'),
  },
  {
    key: 'advanced',
    label: t('licenseUI.advanced_features'),
    icon: CrownOutlined,
    tone: 'license-tone-primary',
    items: props.features.filter((feature) => feature.requiredEdition === 'ADVANCED'),
  },
  {
    key: 'enterprise',
    label: t('licenseUI.enterprise_features'),
    icon: CrownOutlined,
    tone: 'license-tone-violet',
    items: props.features.filter((feature) => feature.requiredEdition === 'ENTERPRISE'),
  },
])

const enabledCount = computed(() => props.features.filter((feature) => feature.enabled).length)

function isFree(feature: LicenseFeatureDef): boolean {
  return feature.requiredEdition === 'BASIC' || feature.enabled
}

function statusLabel(feature: LicenseFeatureDef): string {
  if (isFree(feature)) return t(feature.requiredEdition === 'BASIC' ? 'licenseUI.free' : 'licenseUI.authorized')
  return t(EDITION_CONFIG[feature.requiredEdition].labelKey)
}

function statusTone(feature: LicenseFeatureDef): string {
  return isFree(feature) ? 'status-badge-success' : EDITION_TONE[feature.requiredEdition]
}

function statusIcon(feature: LicenseFeatureDef) {
  return isFree(feature) ? CheckCircleOutlined : LockOutlined
}
</script>

<template>
  <a-card :bordered="false" class="license-matrix-card">
    <template #title>{{ t('licenseUI.feature_matrix') }}</template>
    <template #extra>
      <span class="license-feature-count">
        {{ t('licenseUI.feature_count', { enabled: enabledCount, total: features.length }) }}
      </span>
    </template>

    <a-tabs class="license-matrix-tabs">
      <a-tab-pane v-for="group in groups" :key="group.key">
        <template #tab>
          <span class="license-matrix-tab">
            <component :is="group.icon" :class="group.tone" />
            {{ group.label }}
          </span>
        </template>
        <div class="license-feature-grid">
          <button
            v-for="feature in group.items"
            :key="feature.name"
            type="button"
            class="license-feature-item"
            :class="{ 'is-enabled': feature.enabled }"
            @click="emit('select', feature)"
          >
            <span class="license-feature-icon">
              <component :is="feature.icon" />
            </span>
            <span class="license-feature-content">
              <span class="license-feature-head">
                <span class="license-feature-name">{{ t(feature.displayNameKey) }}</span>
                <span class="status-badge license-mini-chip" :class="statusTone(feature)">
                  <component :is="statusIcon(feature)" />
                  {{ statusLabel(feature) }}
                </span>
              </span>
              <span class="license-feature-desc">{{ t(feature.descriptionKey) }}</span>
            </span>
          </button>
        </div>
      </a-tab-pane>
    </a-tabs>
  </a-card>
</template>