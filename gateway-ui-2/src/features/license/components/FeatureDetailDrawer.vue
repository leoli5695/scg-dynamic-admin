<script setup lang="ts">
/**
 * Feature detail drawer: edition/status chips, overview, key benefits and use
 * cases for one catalog entry. Rendered in a teleported drawer, so its styles
 * live at the top level of license.css.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircleOutlined, RocketOutlined } from '@/components/icons'
import {
  EDITION_CONFIG,
  EDITION_TONE,
  type LicenseFeatureDef,
} from '../constants/featureCatalog'

const props = defineProps<{ feature: LicenseFeatureDef | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()

const editionTone = computed(() =>
  props.feature ? EDITION_TONE[props.feature.requiredEdition] : '',
)

const statusChip = computed(() => {
  const feature = props.feature
  if (!feature) return { label: '', tone: '' }
  if (feature.requiredEdition === 'BASIC') {
    return { label: t('licenseUI.free_to_use'), tone: 'status-badge-success' }
  }
  if (feature.enabled) return { label: t('licenseUI.authorized'), tone: 'status-badge-success' }
  if (feature.requiredEdition === 'ADVANCED') {
    return { label: t('licenseUI.requires_advanced'), tone: 'status-badge-info' }
  }
  return { label: t('licenseUI.requires_enterprise'), tone: 'license-chip-enterprise' }
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
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <template #title>
      <span v-if="feature" class="license-drawer-title">
        <component :is="feature.icon" />
        <span>{{ t(feature.displayNameKey) }}</span>
      </span>
    </template>

    <div v-if="feature" class="license-feature-detail">
      <div class="license-detail-chips">
        <span class="status-badge" :class="editionTone">
          <component :is="EDITION_CONFIG[feature.requiredEdition].icon" />
          {{ t(EDITION_CONFIG[feature.requiredEdition].labelKey) }}
        </span>
        <span class="status-badge" :class="statusChip.tone">{{ statusChip.label }}</span>
      </div>

      <section class="license-detail-section">
        <h4 class="lg-section-title">{{ t('licenseUI.feature_intro') }}</h4>
        <p class="license-detail-copy">
          {{ t(feature.detailedDescriptionKey || feature.descriptionKey) }}
        </p>
      </section>

      <section v-if="feature.benefitKeys?.length" class="license-detail-section">
        <h4 class="lg-section-title">{{ t('licenseUI.key_benefits') }}</h4>
        <ul class="license-detail-list">
          <li v-for="key in feature.benefitKeys" :key="key">
            <CheckCircleOutlined class="license-detail-icon license-tone-success" />
            <span>{{ t(key) }}</span>
          </li>
        </ul>
      </section>

      <section v-if="feature.useCaseKeys?.length" class="license-detail-section">
        <h4 class="lg-section-title">{{ t('licenseUI.use_cases') }}</h4>
        <ul class="license-detail-list">
          <li v-for="key in feature.useCaseKeys" :key="key">
            <RocketOutlined class="license-detail-icon license-tone-primary" />
            <span>{{ t(key) }}</span>
          </li>
        </ul>
      </section>

      <a-alert
        v-if="needsUpgrade"
        type="info"
        show-icon
        class="license-upgrade-alert"
        :message="t('licenseUI.upgrade_hint_title')"
        :description="t('licenseUI.upgrade_hint', { edition: upgradeEditionLabel })"
      />
    </div>
  </a-drawer>
</template>