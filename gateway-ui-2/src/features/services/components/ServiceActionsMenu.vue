<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { CopyOutlined, DeleteOutlined } from '@/components/icons'
import { copyText } from '@/utils/clipboard'
import { message } from '@/utils/feedback'

const props = defineProps<{ serviceId?: string }>()
defineEmits<{ delete: [] }>()
const { t } = useI18n()
const popupContainer = () => document.body

async function copyServiceId() {
  if (!props.serviceId) return
  if (await copyText(props.serviceId)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-dropdown :trigger="['click']" :get-popup-container="popupContainer">
    <a-button class="vn-service-more" :aria-label="t('services.more_actions')" aria-haspopup="menu">
      <span aria-hidden="true">···</span>
    </a-button>
    <template #overlay>
      <a-menu>
        <a-menu-item v-if="serviceId" key="copy-id" @click="copyServiceId">
          <template #icon><CopyOutlined /></template>{{ t('common.copy_id') }}
        </a-menu-item>
        <a-menu-item key="delete" danger @click="$emit('delete')">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>
