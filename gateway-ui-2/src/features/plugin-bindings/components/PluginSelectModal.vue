<script setup lang="ts">
/**
 * Step 1 of the create flow: pick an enabled plugin, grouped by category.
 * Mirrors the React PluginSelectModal (cards instead of a plain list).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Empty } from 'ant-design-vue'
import { ArrowRightOutlined, AppstoreAddOutlined, PlusOutlined } from '@/components/icons'
import { BUILTIN_JAR_PATH, type Plugin } from '@/types/plugin'
import {
  getCategoryIcon,
  getCategoryTone,
  getPluginDescription,
  toneForLegacyColor,
} from '@/features/plugins/utils'
import { categoryLabelKey } from '../utils'

const props = defineProps<{
  plugins: Plugin[]
  loading: boolean
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
}>()
const emit = defineEmits<{
  (e: 'select', plugin: Plugin): void
  (e: 'cancel'): void
}>()

const { t, te, locale } = useI18n()

const isOpen = computed(() => props.open !== false)
const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

const groups = computed(() => {
  const grouped = new Map<string, Plugin[]>()
  props.plugins.forEach((plugin) => {
    const key = plugin.category || 'other'
    const list = grouped.get(key)
    if (list) list.push(plugin)
    else grouped.set(key, [plugin])
  })
  return [...grouped.entries()].map(([key, plugins]) => ({ key, plugins }))
})

function categoryLabel(category?: string | null): string {
  const key = categoryLabelKey(category)
  return key && te(key) ? t(key) : (category || 'other')
}

function toneClass(plugin: Plugin): string {
  const tone = plugin.color ? toneForLegacyColor(plugin.color) : getCategoryTone(plugin.category)
  return `vn-pstone-${tone}`
}

function descriptionOf(plugin: Plugin): string | undefined {
  return getPluginDescription(plugin, locale.value)
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :footer="null"
    :width="880"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal plugin-picker-modal"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true"><PlusOutlined /></span>
        <div>
          <div class="vn-modal-title">{{ t('plugin_bindings.select_plugin') }}</div>
          <div class="vn-modal-subtitle">{{ t('plugin_bindings.select_plugin_desc') }}</div>
        </div>
      </div>
    </template>

    <a-spin :spinning="loading">
      <a-empty
        v-if="plugins.length === 0 && !loading"
        :image="simpleImage"
        class="binding-picker-empty"
      >
        <template #description>
          <span class="binding-picker-empty-text">
            <AppstoreAddOutlined />
            {{ t('plugin_bindings.plugins_empty') }}
          </span>
        </template>
      </a-empty>

      <div v-else class="binding-picker-groups">
        <section v-for="group in groups" :key="group.key" class="binding-picker-group">
          <div class="binding-picker-group-title">
            {{ categoryLabel(group.key) }}
            <span class="binding-picker-group-count">{{ group.plugins.length }}</span>
          </div>
          <div class="binding-picker-grid">
            <button
              v-for="plugin in group.plugins"
              :key="plugin.pluginId"
              type="button"
              class="binding-plugin-card"
              :disabled="loading"
              :title="plugin.pluginName"
              @click="emit('select', plugin)"
            >
              <span :class="['binding-plugin-icon', toneClass(plugin)]">
                <component :is="getCategoryIcon(plugin.category)" />
              </span>
              <span class="binding-plugin-body">
                <span class="binding-plugin-heading">
                  <strong class="binding-plugin-name">{{ plugin.pluginName }}</strong>
                  <span
                    v-if="plugin.jarPath !== BUILTIN_JAR_PATH"
                    class="binding-plugin-kind"
                  >
                    {{ t('plugin_bindings.custom') }}
                  </span>
                </span>
                <span class="binding-plugin-desc">{{ descriptionOf(plugin) || plugin.pluginClass }}</span>
              </span>
              <ArrowRightOutlined class="binding-plugin-arrow" />
            </button>
          </div>
        </section>
      </div>
    </a-spin>
  </a-modal>
</template>