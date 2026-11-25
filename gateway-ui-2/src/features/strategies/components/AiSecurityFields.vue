<script setup lang="ts">
/**
 * AI_SECURITY fields (built-in moderation + optional external adapter).
 * The default block message is persisted into the config and returned to
 * callers by the gateway at runtime, so it stays untranslated.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { StrategyFormValues } from '../utils'
import KeyValueFields from './KeyValueFields.vue'

defineProps<{
  values: StrategyFormValues
}>()

const { t } = useI18n()

const actions = computed(() => [
  { value: 'BLOCK', label: t('strategy.ai_security.action_block') },
  { value: 'REPLACE', label: t('strategy.ai_security.action_replace') },
  { value: 'DETECT', label: t('strategy.ai_security.action_detect') },
])

const piiTypes = computed(() => [
  { value: 'EMAIL', label: t('strategy.ai_security.pii_email') },
  { value: 'PHONE', label: t('strategy.ai_security.pii_phone') },
  { value: 'ID_CARD', label: t('strategy.ai_security.pii_id_card') },
])

const wordLists = computed(() => [
  { name: 'bannedWords', label: t('strategy.ai_security.banned_words') },
  { name: 'prohibitedWords', label: t('strategy.ai_security.prohibited_words') },
  { name: 'bannedPatterns', label: t('strategy.ai_security.banned_patterns') },
])

const streamingOptions = computed(() => [
  { value: 'BUFFER', label: t('strategy.ai_security.streaming_buffer') },
  { value: 'BLOCK', label: t('strategy.ai_security.streaming_block') },
  { value: 'SKIP', label: t('strategy.ai_security.streaming_skip') },
])

const failureOptions = computed(() => [
  { value: 'BLOCK', label: t('strategy.ai_security.external_failure_block') },
  { value: 'LOCAL', label: t('strategy.ai_security.external_failure_local') },
])

const adapterPattern = /^[A-Za-z0-9_]{1,64}$/
const adapterRules = computed(() => [
  { pattern: adapterPattern, message: t('strategy.ai_security.external_adapter_pattern') },
])
</script>

<template>
  <div>
    <a-alert type="info" show-icon class="strategy-alert" :message="t('strategy.ai_security.builtin_hint')" />
    <a-row :gutter="16">
      <a-col :span="12">
        <a-form-item :name="'scanRequest'" :label="t('strategy.ai_security.scan_request')">
          <a-switch v-model:checked="values.scanRequest" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item :name="'scanResponse'" :label="t('strategy.ai_security.scan_response')">
          <a-switch v-model:checked="values.scanResponse" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item :name="'inboundAction'" :label="t('strategy.ai_security.inbound_action')">
          <a-select v-model:value="values.inboundAction" :options="actions"  popup-class-name="vn-portal"/>
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item :name="'outboundAction'" :label="t('strategy.ai_security.outbound_action')">
          <a-select v-model:value="values.outboundAction" :options="actions"  popup-class-name="vn-portal"/>
        </a-form-item>
      </a-col>
    </a-row>

    <a-form-item
      v-for="list in wordLists"
      :key="list.name"
      :name="list.name"
      :label="list.label"
      :extra="t('strategy.ai_security.word_list_extra')"
    >
      <a-select
        v-model:value="values[list.name]"
        mode="tags"
        :open="false"
        :placeholder="t('strategy.ai_security.word_list_placeholder')"
       popup-class-name="vn-portal"/>
    </a-form-item>

    <a-row :gutter="16">
      <a-col :span="8">
        <a-form-item :name="'enablePiiMasking'" :label="t('strategy.ai_security.enable_pii')">
          <a-switch v-model:checked="values.enablePiiMasking" />
        </a-form-item>
      </a-col>
      <a-col :span="16">
        <a-form-item :name="'piiAction'" :label="t('strategy.ai_security.pii_action')">
          <a-select v-model:value="values.piiAction" :options="actions"  popup-class-name="vn-portal"/>
        </a-form-item>
      </a-col>
    </a-row>

    <a-form-item :name="'piiTypes'" :label="t('strategy.ai_security.pii_types')">
      <a-select v-model:value="values.piiTypes" mode="multiple" :options="piiTypes"  popup-class-name="vn-portal"/>
    </a-form-item>

    <a-form-item
      :name="'blockMessage'"
      :label="t('strategy.ai_security.block_message')"
      :rules="[{ required: true }]"
    >
      <a-input v-model:value="values.blockMessage" />
    </a-form-item>

    <a-form-item
      :name="'streamingPolicy'"
      :label="t('strategy.ai_security.streaming_policy')"
      :extra="t('strategy.ai_security.streaming_policy_extra')"
    >
      <a-select v-model:value="values.streamingPolicy" :options="streamingOptions"  popup-class-name="vn-portal"/>
    </a-form-item>

    <a-row :gutter="16">
      <a-col :span="12">
        <a-form-item :name="'maxBodySize'" :label="t('strategy.ai_security.max_body_size')">
          <a-input-number v-model:value="values.maxBodySize" :min="1024" :max="10485760" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item :name="'responseTimeoutMs'" :label="t('strategy.ai_security.response_timeout')">
          <a-input-number v-model:value="values.responseTimeoutMs" :min="1000" :max="300000" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>

    <a-typography-title :level="5">{{ t('strategy.ai_security.external_title') }}</a-typography-title>
    <a-alert type="info" show-icon class="strategy-alert" :message="t('strategy.ai_security.external_hint')" />

    <a-form-item :name="'externalUrl'" :label="t('strategy.ai_security.external_url')">
      <a-input
        v-model:value="values.externalUrl"
        :placeholder="t('strategy.ai_security.external_url_placeholder')"
      />
    </a-form-item>
    <a-form-item :name="'externalAdapter'" :label="t('strategy.ai_security.external_adapter')" :rules="adapterRules">
      <a-input v-model:value="values.externalAdapter" />
    </a-form-item>

    <KeyValueFields
      v-model="values.externalHeaderRows"
      name="externalHeaderRows"
      :label="t('strategy.ai_security.external_headers')"
    />
    <KeyValueFields
      v-model="values.externalOptionRows"
      name="externalOptionRows"
      :label="t('strategy.ai_security.external_options')"
    />

    <a-row :gutter="16">
      <a-col :span="12">
        <a-form-item :name="'externalTimeoutMs'" :label="t('strategy.ai_security.external_timeout')">
          <a-input-number v-model:value="values.externalTimeoutMs" :min="100" :max="30000" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item :name="'externalFailureAction'" :label="t('strategy.ai_security.external_failure_action')">
          <a-select v-model:value="values.externalFailureAction" :options="failureOptions"  popup-class-name="vn-portal"/>
        </a-form-item>
      </a-col>
    </a-row>
  </div>
</template>