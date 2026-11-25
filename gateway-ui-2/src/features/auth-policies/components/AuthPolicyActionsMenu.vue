<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { CopyOutlined, DeleteOutlined, LinkOutlined, QuestionCircleOutlined } from '@/components/icons'
import { copyText } from '@/utils/clipboard'
import { message } from '@/utils/feedback'

const props = defineProps<{ policyId?: string }>()
defineEmits<{ bind: []; usage: []; delete: [] }>()
const { t } = useI18n()
const popupContainer = () => document.body

async function copyPolicyId() {
  if (!props.policyId) return
  if (await copyText(props.policyId)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-dropdown :trigger="['click']" :get-popup-container="popupContainer">
    <a-button class="vn-more-btn" :aria-label="t('auth_policy.more_actions')" aria-haspopup="menu">
      <span aria-hidden="true">···</span>
    </a-button>
    <template #overlay>
      <a-menu>
        <a-menu-item key="bind" @click="$emit('bind')">
          <template #icon><LinkOutlined /></template>{{ t('auth_policy.bind_routes') }}
        </a-menu-item>
        <a-menu-item key="usage" @click="$emit('usage')">
          <template #icon><QuestionCircleOutlined /></template>{{ t('auth_policy.usage_example') }}
        </a-menu-item>
        <a-menu-item v-if="policyId" key="copy-id" @click="copyPolicyId">
          <template #icon><CopyOutlined /></template>{{ t('common.copy_id') }}
        </a-menu-item>
        <a-menu-item key="delete" danger @click="$emit('delete')">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>