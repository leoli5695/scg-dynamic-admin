<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { CopyOutlined, DeleteOutlined } from '@/components/icons'
import { copyText } from '@/utils/clipboard'
import { message } from '@/utils/feedback'

const props = defineProps<{ strategyId?: string }>()
defineEmits<{ delete: [] }>()
const { t } = useI18n()
const popupContainer = () => document.body

async function copyStrategyId() {
  if (!props.strategyId) return
  if (await copyText(props.strategyId)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-dropdown :trigger="['click']" :get-popup-container="popupContainer">
    <a-button class="vn-more-btn" :aria-label="t('strategy.more_actions')" aria-haspopup="menu">
      <span aria-hidden="true">···</span>
    </a-button>
    <template #overlay>
      <a-menu>
        <a-menu-item v-if="strategyId" key="copy-id" @click="copyStrategyId">
          <template #icon><CopyOutlined /></template>{{ t('common.copy_id') }}
        </a-menu-item>
        <a-menu-item key="delete" danger @click="$emit('delete')">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>