<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Select } from 'ant-design-vue'
import { GlobalOutlined } from '@/components/icons'

const { locale } = useI18n()

const languages = [
  { value: 'en', label: 'English' },
  { value: 'zh', label: '中文' },
]

const currentLang = computed(() => (locale.value || 'en').substring(0, 2))

function handleChange(value: unknown) {
  const lang = String(value)
  locale.value = lang
  // Persist under the same key the legacy app used, so the choice survives reloads.
  localStorage.setItem('i18nextLng', lang)
  document.documentElement.lang = lang
}
</script>

<template>
  <Select
    :value="currentLang"
    :options="languages"
    class="header-select"
    popup-class-name="language-switcher-dropdown"
    @change="handleChange"
  >
    <template #suffixIcon>
      <GlobalOutlined />
    </template>
  </Select>
</template>

<style scoped>
.header-select {
  width: 104px;
}
</style>