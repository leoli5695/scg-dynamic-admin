<script setup lang="ts">
/**
 * Load balancer strategy selector, shared by create/edit service forms.
 * Controlled via v-model:value so it can sit inside an a-form-item.
 * The closed control shows the localized name; the dropdown adds a description.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { AimOutlined, ForkOutlined, SwapOutlined, TrophyOutlined } from '@/components/icons'

defineProps<{ value?: string }>()
const emit = defineEmits<{ (e: 'update:value', v: string): void }>()

const { t } = useI18n()

const options = computed(() => [
  { value: 'weighted', icon: TrophyOutlined, title: t('services.weighted'), desc: t('services.lb_weighted_desc') },
  { value: 'round-robin', icon: SwapOutlined, title: t('services.round_robin'), desc: t('services.lb_round_robin_desc') },
  { value: 'random', icon: AimOutlined, title: t('services.random'), desc: t('services.lb_random_desc') },
  { value: 'consistent-hash', icon: ForkOutlined, title: t('services.consistent_hash'), desc: t('services.lb_consistent_hash_desc') },
])
</script>

<template>
  <a-select
    :value="value"
    class="select-with-icon"
    popup-class-name="vn-portal loadBalancer-dropdown"
    option-label-prop="label"
    :list-height="280"
    @change="emit('update:value', $event)"
  >
    <a-select-option v-for="opt in options" :key="opt.value" :value="opt.value" :label="opt.title">
      <span class="option-wrapper">
        <component :is="opt.icon" class="option-icon" />
        <span class="option-text">
          <span class="option-title">{{ opt.title }}</span>
          <span class="option-desc">{{ opt.desc }}</span>
        </span>
      </span>
    </a-select-option>
  </a-select>
</template>