<script setup lang="ts">
/**
 * Feature detail drawer on the Vue-native layer: edition/status badges,
 * overview, key benefits and use cases for one catalog entry. Kept mounted and
 * driven through `open`.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircleOutlined, CloseOutlined, RocketOutlined } from '@/components/icons'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import {
  EDITION_CONFIG,
  type LicenseFeatureDef,
} from '../constants/featureCatalog'

const props = defineProps<{ feature: LicenseFeatureDef | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()

function editionTone(edition: string): 'ok' | 'info' | 'warn' {
  if (edition === 'BASIC') return 'ok'
  return edition === 'ADVANCED' ? 'info' : 'warn'
}

const statusChip = computed(() => {
  const feature = props.feature
  if (!feature) return { label: '', tone: 'idle' as const }
  if (feature.requiredEdition === 'BASIC') return { label: t('licenseUI.free_to_use'), tone: 'ok' as const }
  if (feature.enabled) return { label: t('licenseUI.authorized'), tone: 'ok' as const }
  if (feature.requiredEdition === 'ADVANCED') return { label: t('licenseUI.requires_advanced'), tone: 'info' as const }
  return { label: t('licenseUI.requires_enterprise'), tone: 'warn' as const }
})

const needsUpgrade = computed(() =>
  !!props.feature && props.feature.requiredEdition !== 'BASIC' && !props.feature.enabled,
)

const upgradeEditionLabel = computed(() =>
  props.feature ? t(EDITION_CONFIG[props.feature.requiredEdition].labelKey) : '',
)
</script>

<template>
  <a-drawer
    :open="!!feature"
    placement="right"
    :width="480"
    :closable="false"
    :mask-closable="true"
    root-class-name="vn-portal vnp-portal vnp-drawer"
    @close="emit('close')"
  >
    <div v-if="feature" class="vnp-detail">
      <div class="vnp-detail-head">
        <div class="vnp-detail-head-left">
          <span class="vnp-detail-glyph" aria-hidden="true"><component :is="feature.icon" /></span>
          <div style="min-width: 0">
            <h4 class="vnp-detail-title">{{ t(feature.displayNameKey) }}</h4>
          </div>
        </div>
        <VnIconButton :icon="CloseOutlined" :label="t('common.close')" @click="emit('close')" />
      </div>

      <div class="vnp-detail-scroll">
        <div class="vnp-detail-pills">
          <VnBadge :tone="editionTone(feature.requiredEdition)">
            <component :is="EDITION_CONFIG[feature.requiredEdition].icon" />
            {{ t(EDITION_CONFIG[feature.requiredEdition].labelKey) }}
          </VnBadge>
          <VnBadge :tone="statusChip.tone">{{ statusChip.label }}</VnBadge>
        </div>

        <section class="vnp-section">
          <div class="vnp-section-head">{{ t('licenseUI.feature_intro') }}</div>
          <p class="vnp-detail-desc">{{ t(feature.detailedDescriptionKey || feature.descriptionKey) }}</p>
        </section>

        <section v-if="feature.benefitKeys?.length" class="vnp-section">
          <div class="vnp-section-head">{{ t('licenseUI.key_benefits') }}</div>
          <ul class="vn-license-detail-list">
            <li v-for="key in feature.benefitKeys" :key="key">
              <CheckCircleOutlined class="vn-ink-ok" />
              <span>{{ t(key) }}</span>
            </li>
          </ul>
        </section>

        <section v-if="feature.useCaseKeys?.length" class="vnp-section">
          <div class="vnp-section-head">{{ t('licenseUI.use_cases') }}</div>
          <ul class="vn-license-detail-list">
            <li v-for="key in feature.useCaseKeys" :key="key">
              <RocketOutlined class="vn-ink-info" />
              <span>{{ t(key) }}</span>
            </li>
          </ul>
        </section>

        <a-alert
          v-if="needsUpgrade"
          type="info"
          show-icon
          :message="t('licenseUI.upgrade_hint_title')"
          :description="t('licenseUI.upgrade_hint', { edition: upgradeEditionLabel })"
        />
      </div>
    </div>
  </a-drawer>
</template>