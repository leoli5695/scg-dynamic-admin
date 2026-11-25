<script setup lang="ts">
/**
 * Usage example modal — fetches GET /api/auth/policies/{id}/usage-example
 * and renders auth-type-specific header/curl snippets as copyable code blocks.
 */
import { useI18n } from 'vue-i18n'
import {
  ExclamationCircleOutlined, QuestionCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { getAuthPolicyUsageExample } from '@/api/authPolicies'
import { useRequest } from '@/composables/useRequest'
import type { AuthPolicyDefinition } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import CodeBlock from './CodeBlock.vue'

const props = defineProps<{
  policy: AuthPolicyDefinition
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()

const { data: example, loading, error, run } = useRequest(
  (signal) => getAuthPolicyUsageExample(props.policy.policyId ?? '', { signal }),
  {
    deps: [],
    onError: () => message.error(t('auth_policy.usage_example_failed')),
  },
)
</script>

<template>
  <a-modal
    :open="true"
    :width="720"
    :footer="null"
    wrap-class-name="auth-policy-usage-modal"
    destroy-on-close
    @cancel="emit('close')"
  >
    <template #title>
      <div class="auth-modal-head">
        <span class="auth-modal-glyph is-usage" aria-hidden="true"><QuestionCircleOutlined /></span>
        <span class="auth-modal-heading">
          <span class="auth-modal-title">{{ t('auth_policy.usage_example') }}</span>
          <span class="auth-modal-subtitle">{{ policy.policyName }} ({{ policy.authType }})</span>
        </span>
      </div>
    </template>

    <a-spin :spinning="loading">
      <div v-if="error" class="auth-state auth-state-inline">
        <span class="auth-state-glyph is-error" aria-hidden="true"><ExclamationCircleOutlined /></span>
        <h4 class="auth-state-title">{{ t('auth_policy.usage_example_failed') }}</h4>
        <p class="auth-state-desc">{{ error.message }}</p>
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
            <span class="status-badge status-badge-success">
              {{ t('auth_policy.token_expires') }}: {{ example.tokenExpiresIn }}
            </span>
            <span class="status-badge status-badge-info">
              {{ t('auth_policy.algorithm') }}: {{ example.algorithm }}
            </span>
          </div>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'API_KEY'">
          <CodeBlock
            :label="`${t('auth_policy.api_key_header_format')} · ${example.headerName}`"
            :code="example.headerValue"
          />
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <div v-if="example.prefixNote" class="auth-usage-chips">
            <span class="status-badge status-badge-warning">{{ example.prefixNote }}</span>
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
            <span class="status-badge status-badge-info">{{ t('auth_policy.realm') }}: {{ example.realm }}</span>
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
            <span class="status-badge status-badge-info">{{ t('auth_policy.client_id') }}: {{ example.clientId }}</span>
            <span class="status-badge status-badge-success">{{ t('auth_policy.token_endpoint') }}: {{ example.tokenEndpoint }}</span>
            <span v-if="example.requiredScopes" class="status-badge status-badge-warning">
              {{ t('auth_policy.required_scopes') }}: {{ example.requiredScopes }}
            </span>
          </div>
          <p v-if="example.note" class="auth-usage-note">{{ example.note }}</p>
        </template>

        <template v-else-if="example.authType?.toUpperCase() === 'HMAC'">
          <div class="auth-usage-block">
            <span class="auth-usage-label">{{ t('auth_policy.hmac_required_headers') }}</span>
            <div class="auth-usage-chips">
              <span
                v-for="(header, index) in example.requiredHeaders ?? []"
                :key="`${header.name}-${index}`"
                class="status-badge status-badge-info"
              >
                {{ header.name }}: {{ header.value }}
              </span>
            </div>
          </div>
          <div class="auth-usage-block">
            <span class="auth-usage-label">{{ t('auth_policy.hmac_signature_calc') }}</span>
            <code class="auth-inline-code">{{ example.signatureCalculation }}</code>
            <div class="auth-usage-muted">{{ example.stringToSignFormat }}</div>
          </div>
          <CodeBlock v-if="example.curlExample" :label="t('auth_policy.curl_example')" :code="example.curlExample" />
          <div class="auth-usage-chips">
            <span class="status-badge status-badge-info">
              {{ t('auth_policy.signature_algorithm') }}: {{ example.signatureAlgorithm }}
            </span>
            <span class="status-badge status-badge-warning">
              {{ t('auth_policy.access_key') }}: {{ example.accessKey }}
            </span>
          </div>
          <p v-if="example.note" class="auth-usage-note">{{ example.note }}</p>
        </template>

        <div v-else class="auth-usage-note">{{ t('auth_policy.no_example_available') }}</div>
      </template>
    </a-spin>

    <div class="auth-modal-footer">
      <a-button @click="emit('close')">{{ t('common.close') }}</a-button>
    </div>
  </a-modal>
</template>