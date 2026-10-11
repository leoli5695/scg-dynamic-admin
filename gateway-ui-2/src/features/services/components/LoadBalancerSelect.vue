<script setup lang="ts">
/**
 * Load balancer strategy selector, shared by create/edit service forms.
 * Controlled via v-model:value so it can sit inside an a-form-item.
 */
import { useI18n } from 'vue-i18n'
import {
  AimOutlined, ForkOutlined, LinkOutlined, ReloadOutlined,
  SwapOutlined, ThunderboltOutlined, TrophyOutlined,
} from '@ant-design/icons-vue'

defineProps<{ value?: string }>()
const emit = defineEmits<{ (e: 'update:value', v: string): void }>()

const { t } = useI18n()

const options = [
  { value: 'weighted', icon: TrophyOutlined, labelIcon: TrophyOutlined, title: t('services.weighted'), desc: '平滑加权轮询，推荐', iconClass: 'option-icon-weighted' },
  { value: 'round-robin', icon: SwapOutlined, labelIcon: ReloadOutlined, title: t('services.round_robin'), desc: '简单轮询分发', iconClass: 'option-icon-roundrobin' },
  { value: 'random', icon: AimOutlined, labelIcon: ThunderboltOutlined, title: t('services.random'), desc: '随机选择', iconClass: 'option-icon-random' },
  { value: 'consistent-hash', icon: ForkOutlined, labelIcon: LinkOutlined, title: t('services.consistent_hash'), desc: '基于哈希值，适合会话保持', iconClass: 'option-icon-hash' },
]
</script>

<template>
  <a-select
    :value="value"
    size="large"
    class="select-with-icon"
    popup-class-name="loadBalancer-dropdown"
    option-label-prop="label"
    :list-height="280"
    @change="emit('update:value', $event)"
  >
    <a-select-option v-for="opt in options" :key="opt.value" :value="opt.value">
      <template #label>
        <span class="custom-select-label">
          <component :is="opt.labelIcon" class="option-icon" />
          <span class="option-title">{{ opt.title }}</span>
        </span>
      </template>
      <span class="option-wrapper">
        <component :is="opt.icon" :class="['option-icon', opt.iconClass]" />
        <span class="option-text">
          <span class="option-title">{{ opt.title }}</span>
          <span class="option-desc">{{ opt.desc }}</span>
        </span>
      </span>
    </a-select-option>
  </a-select>
</template>
