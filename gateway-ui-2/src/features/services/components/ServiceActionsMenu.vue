<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { DeleteOutlined } from '@/components/icons'

defineEmits<{ delete: [] }>()
const { t } = useI18n()
const popupContainer = () => document.body
</script>

<template>
  <a-dropdown :trigger="['click']" :get-popup-container="popupContainer">
    <a-button class="vn-service-more" :aria-label="t('services.more_actions')" aria-haspopup="menu">
      <span aria-hidden="true">···</span>
    </a-button>
    <template #overlay>
      <a-menu>
        <a-menu-item key="delete" danger @click="$emit('delete')">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>
