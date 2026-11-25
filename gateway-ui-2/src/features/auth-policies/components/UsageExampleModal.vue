<script setup lang="ts">
/**
 * Usage example modal — fetches GET /api/auth/policies/{id}/usage-example
 * and renders auth-type-specific header/curl snippets as copyable code blocks.
 */
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ExclamationCircleOutlined, QuestionCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { getAuthPolicyUsageExample } from '@/api/authPolicies'
import { useRequest } from '@/composables/useRequest'
import type { AuthPolicyDefinition } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import VnBadge from '@/components/vn/VnBadge.vue'
import CodeBlock from './CodeBlock.vue'

const props = defineProps<{
  policy?: AuthPolicyDefinition
  /** Kept mounted by the page and toggled here. */
  open?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()

const isOpen = computed(() => props.open !== false)

const { data: example, loading, error, run } = useRequest(
  (signal) => getAuthPolicyUsageExample(props.policy?.policyId ?? '', { signal }),
  {
    immediate: false,
    deps: [],
    onError: () => message.error(t('auth_policy.usage_example_failed')),
  },
)

watch(isOpen, (open) => {
  if (open && props.policy?.policyId) run()
})
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="640"
    :footer="null"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true"><QuestionCircleOutlined /></span>
        <div>
          <div class="vn-modal-title">{{ t('auth_policy.usage_example') }}</div>
          <div class="vn-modal-subtitle">{{ policy?.policyName }} ({{ policy?.authType }})</div>
        </div>
      </div>
    </template>

    <a-spin :spinning="loading">
      <div class="auth-usage-scroll">
      <div v-if="error" class="auth-state-inline">
        <span class="auth-confirm-glyph" aria-hidden="true"><ExclamationCircleOutlined /></span>
        <h4 class="auth-confirm-title">{{ t('auth_policy.usage_example_failed') }}</h4>
        <p class="auth-confirm-desc">{{ error.message }}</p>
        <a-button @click="run">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
      </div>

      <template v-else-if="example">
        <div v-if="example.error" class="auth-usage-error">{{ example.error }}</div>

        <template v-else-if="example.authType?.toUpperCase() === 'JWT'">
          <CodeBlock :label="t('auth_policy.jwt_header_format')" :code="example.headerValue" />
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <div v-if="example.tokenExpiresIn" class="auth-usage-chips">
            <VnBadge tone="ok">
              {{ t('auth_policy.token_expires') }}: {{ example.tokenExpiresIn }}
            </VnBadge>
            <VnBadge tone="info">
              {{ t('auth_policy.algorithm') }}: {{ example.algorithm }}
            </VnBadge>
          </div>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'API_KEY'">
          <CodeBlock
            :label="`${t('auth_policy.api_key_header_format')} · ${example.headerName}`"
            :code="example.headerValue"
          />
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <div v-if="example.prefixNote" class="auth-usage-chips">
            <VnBadge tone="warn">{{ example.prefixNote }}</VnBadge>
          </div>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'BASIC'">
          <CodeBlock :label="t('auth_policy.basic_header_format')" :code="example.headerValue" />
          <div class="auth-usage-block">
            <span class="auth-usage-label">{{ t('auth_policy.credentials') }}</span>
            <div class="auth-usage-credentials">
              <code class="auth-inline-code">{{ example.credentialsFormat }}</code>
              <span class="auth-usage-muted">
                {{ t('auth_policy.credentials_base64', { value: example.credentialsEncoded ?? '' }) }}
              </span>
            </div>
          </div>
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <CodeBlock
            v-if="example.curlWithAuthFlag"
            :label="t('auth_policy.curl_alternative')"
            :code="example.curlWithAuthFlag"
          />
          <div v-if="example.realm" class="auth-usage-chips">
            <VnBadge tone="info">{{ t('auth_policy.realm') }}: {{ example.realm }}</VnBadge>
          </div>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'OAUTH2'">
          <CodeBlock
            v-if="example.getTokenCurl"
            :label="t('auth_policy.oauth2_get_token')"
            :code="example.getTokenCurl"
          />
          <CodeBlock :label="t('auth_policy.oauth2_use_token')" :code="example.headerValue" />
          <div class="auth-usage-chips">
            <VnBadge tone="info">{{ t('auth_policy.client_id') }}: {{ example.clientId }}</VnBadge>
            <VnBadge tone="ok">{{ t('auth_policy.token_endpoint') }}: {{ example.tokenEndpoint }}</VnBadge>
            <VnBadge v-if="example.requiredScopes" tone="warn">
              {{ t('auth_policy.required_scopes') }}: {{ example.requiredScopes }}
            </VnBadge>
          </div>
          <p v-if="example.note" class="auth-usage-note">{{ example.note }}</p>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'HMAC'">
          <div class="auth-usage-block">
            <span class="auth-usage-label">{{ t('auth_policy.hmac_required_headers') }}</span>
            <div class="auth-usage-chips">
              <VnBadge
                v-for="(header, index) in example.requiredHeaders ?? []"
                :key="`${header.name}-${index}`"
                tone="info"
              >
                {{ header.name }}: {{ header.value }}
              </VnBadge>
            </div>
          </div>
          <div class="auth-usage-block">
            <span class="auth-usage-label">{{ t('auth_policy.hmac_signature_calc') }}</span>
            <code class="auth-inline-code">{{ example.signatureCalculation }}</code>
            <div class="auth-usage-muted">{{ example.stringToSignFormat }}</div>
          </div>
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <div class="auth-usage-chips">
            <VnBadge tone="info">
              {{ t('auth_policy.signature_algorithm') }}: {{ example.signatureAlgorithm }}
            </VnBadge>
            <VnBadge tone="warn">
              {{ t('auth_policy.access_key') }}: {{ example.accessKey }}
            </VnBadge>
          </div>
          <p v-if="example.note" class="auth-usage-note">{{ example.note }}</p>
        </template>

        <div v-else class="auth-usage-note">{{ t('auth_policy.no_example_available') }}</div>
      </template>
      </div>
    </a-spin>

    <div class="modal-footer-modern">
      <a-button @click="emit('close')">{{ t('common.close') }}</a-button>
    </div>
  </a-modal>
</template>