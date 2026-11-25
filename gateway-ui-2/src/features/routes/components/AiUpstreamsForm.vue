<script setup lang="ts">
/**
 * AI route binding form — selects AI services (LLM providers) as weighted upstreams
 * and configures model-match predicates. Used by RouteFormModal when category === 'AI'.
 * Field shape mirrors backend model/AiUpstream.java + model/AiModelPredicate.java.
 * Edits the reactive arrays owned by the parent form.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined, RobotOutlined } from '@/components/icons'
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
    <div v-for="(up, idx) in aiUpstreams" :key="idx" class="upstream-card">
      <div class="upstream-head">
        <span class="upstream-index">{{ t('routes.ai_upstream') }} {{ idx + 1 }}</span>
        <a-tooltip :title="t('common.delete')">
          <a-button type="text" danger class="action-btn vn-iconbtn" :aria-label="t('common.delete')" @click="removeUpstream(idx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
      <div class="upstream-controls">
        <a-select
          v-model:value="up.provider"
          :placeholder="t('routes.ai_select_service')"
          class="upstream-service-select"
          show-search
          option-filter-prop="label"
          popup-class-name="vn-portal"
          :options="serviceOptions"
          :not-found-content="t('routes.ai_no_services')"
        />
        <a-input-number v-model:value="up.weight" :min="1" :max="100" class="upstream-weight" addon-after="%" />
      </div>

      <!-- Per-upstream model mapping -->
      <div class="mapping-block">
        <div class="mapping-title" :title="t('routes.ai_model_mapping_hint')">
          {{ t('routes.ai_model_mapping') }}
        </div>
        <div v-for="(mm, mmIdx) in (up.modelMappingPairs ?? [])" :key="mmIdx" class="mapping-row">
          <a-input v-model:value="mm.key" :placeholder="t('routes.ai_model_mapping_key')" size="small" class="mapping-input" />
          <span class="mapping-arrow">→</span>
          <a-input v-model:value="mm.value" :placeholder="t('routes.ai_model_mapping_value')" size="small" class="mapping-input" />
          <a-button type="text" danger size="small" class="action-btn vn-iconbtn" :aria-label="t('common.delete')" @click="removeMapping(up, mmIdx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </div>
        <a-button type="link" size="small" class="inline-add-btn" @click="addMapping(up)">
          <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_model_mapping') }}
        </a-button>
      </div>
    </div>
    <a-button type="dashed" block class="add-predicate-btn-modern" @click="addUpstream">
      <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_upstream') }}
    </a-button>

    <!-- Model predicates -->
    <div class="model-predicates">
      <div class="subsection-title">
        <RobotOutlined />{{ t('routes.ai_model_predicates') }}
      </div>
      <div v-for="(mp, mpIdx) in modelPredicates" :key="mpIdx" class="model-predicate-row">
        <a-select v-model:value="mp.matchType" class="model-match-type" popup-class-name="vn-portal">
          <a-select-option value="PREFIX">{{ t('routes.ai_match_prefix') }}</a-select-option>
          <a-select-option value="EXACT">{{ t('routes.ai_match_exact') }}</a-select-option>
        </a-select>
        <a-input v-model:value="mp.value" :placeholder="t('routes.ai_model_value_placeholder')" class="model-match-value" />
        <a-tooltip :title="t('common.delete')">
          <a-button type="text" danger class="action-btn vn-iconbtn" :aria-label="t('common.delete')" @click="removePredicate(mpIdx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
      <a-button type="dashed" block class="add-predicate-btn-modern" @click="addPredicate">
        <template #icon><PlusOutlined /></template>{{ t('routes.ai_add_model_predicate') }}
      </a-button>
    </div>
  </div>
</template>
