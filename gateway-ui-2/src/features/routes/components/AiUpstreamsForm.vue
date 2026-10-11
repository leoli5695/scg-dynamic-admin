<script setup lang="ts">
/**
 * AI route binding form — selects AI services (LLM providers) as weighted upstreams
 * and configures model-match predicates. Used by RouteFormModal when category === 'AI'.
 * Field shape mirrors backend model/AiUpstream.java + model/AiModelPredicate.java.
 * Edits the reactive arrays owned by the parent form.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons-vue'
import type { ServiceDefinition } from '@/types/service'

interface ModelMappingPair { key?: string; value?: string }
interface AiUpstream { provider?: string; weight?: number; modelMappingPairs?: ModelMappingPair[] }
interface ModelPredicate { matchType?: string; value?: string }

const props = defineProps<{
  aiServices: ServiceDefinition[]
  aiUpstreams: AiUpstream[]
  modelPredicates: ModelPredicate[]
}>()

const { t } = useI18n()

const serviceOptions = computed(() => props.aiServices.map((s) => ({
  label: s.providerType ? `${s.name} (${s.providerType})` : s.name,
  value: s.name,
})))

function addUpstream() {
  props.aiUpstreams.push({ weight: 100, modelMappingPairs: [] })
}
function removeUpstream(index: number) {
  props.aiUpstreams.splice(index, 1)
}
function addMapping(up: AiUpstream) {
  if (!up.modelMappingPairs) up.modelMappingPairs = []
  up.modelMappingPairs.push({ key: '', value: '' })
}
function removeMapping(up: AiUpstream, index: number) {
  up.modelMappingPairs?.splice(index, 1)
}
function addPredicate() {
  props.modelPredicates.push({ matchType: 'PREFIX', value: '' })
}
function removePredicate(index: number) {
  props.modelPredicates.splice(index, 1)
}
</script>

<template>
  <div>
    <!-- AI service upstreams -->
    <div
      v-for="(up, idx) in aiUpstreams"
      :key="idx"
      :style="{ padding: '10px 12px', marginBottom: '8px', borderRadius: '6px', border: '1px solid rgba(148,163,184,0.2)' }"
    >
      <div :style="{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }">
        <a-select
          v-model:value="up.provider"
          :placeholder="t('routes.ai_select_service')"
          :style="{ width: '300px', flexShrink: 0 }"
          show-search
          option-filter-prop="label"
          :options="serviceOptions"
          :not-found-content="t('routes.ai_no_services')"
        />
        <a-input-number v-model:value="up.weight" :min="1" :max="100" :style="{ width: '100px', flexShrink: 0 }" addon-after="%" />
        <a-button type="text" danger :style="{ flexShrink: 0, padding: '4px' }" @click="removeUpstream(idx)">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </div>

      <!-- Per-upstream model mapping -->
      <div :style="{ marginTop: '10px' }">
        <div :style="{ fontSize: '12px', opacity: 0.75, marginBottom: '4px' }" :title="t('routes.ai_model_mapping_hint')">
          {{ t('routes.ai_model_mapping') }}
        </div>
        <div
          v-for="(mm, mmIdx) in (up.modelMappingPairs ?? [])"
          :key="mmIdx"
          :style="{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px', flexWrap: 'nowrap' }"
        >
          <a-input v-model:value="mm.key" :placeholder="t('routes.ai_model_mapping_key')" size="small" :style="{ flex: 1 }" />
          <span :style="{ opacity: 0.5, flexShrink: 0 }">→</span>
          <a-input v-model:value="mm.value" :placeholder="t('routes.ai_model_mapping_value')" size="small" :style="{ flex: 1 }" />
          <a-button type="text" danger size="small" :style="{ flexShrink: 0, padding: '2px' }" @click="removeMapping(up, mmIdx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </div>
        <a-button type="link" size="small" :style="{ padding: 0 }" @click="addMapping(up)">
          <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_model_mapping') }}
        </a-button>
      </div>
    </div>
    <a-button type="dashed" block @click="addUpstream">
      <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_upstream') }}
    </a-button>

    <!-- Model predicates -->
    <div :style="{ marginTop: '16px' }">
      <div :style="{ marginBottom: '8px', fontWeight: 500 }">
        <RobotOutlined :style="{ marginRight: '6px' }" />{{ t('routes.ai_model_predicates') }}
      </div>
      <div
        v-for="(mp, mpIdx) in modelPredicates"
        :key="mpIdx"
        :style="{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px', flexWrap: 'nowrap' }"
      >
        <a-select v-model:value="mp.matchType" :style="{ width: '120px', flexShrink: 0 }">
          <a-select-option value="PREFIX">{{ t('routes.ai_match_prefix') }}</a-select-option>
          <a-select-option value="EXACT">{{ t('routes.ai_match_exact') }}</a-select-option>
        </a-select>
        <a-input v-model:value="mp.value" :placeholder="t('routes.ai_model_value_placeholder')" :style="{ flex: 1 }" />
        <a-button type="text" danger :style="{ flexShrink: 0, padding: '4px' }" @click="removePredicate(mpIdx)">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </div>
      <a-button type="dashed" block @click="addPredicate">
        <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_model_predicate') }}
      </a-button>
    </div>
  </div>
</template>
