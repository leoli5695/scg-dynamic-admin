<script setup lang="ts">
/**
 * Plugin development guide — info alert + collapsible example template.
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { BookOutlined, DownloadOutlined } from '@/components/icons';
import { message } from '@/utils/feedback';

const { t } = useI18n();

const steps = computed(() => [
  t('plugins.dev_step_1'),
  t('plugins.dev_step_2'),
  t('plugins.dev_step_3'),
  t('plugins.dev_step_4'),
  t('plugins.dev_step_5'),
  t('plugins.dev_step_6'),
  t('plugins.dev_step_7'),
]);

const schemaExample = computed(() => `{
  "fields": [
    {
      "name": "maxRequests",
      "type": "number",
      "label": "${t('plugins.schema_field_max_requests')}",
      "default": 100,
      "min": 1,
      "max": 10000
    },
    {
      "name": "keyResolver",
      "type": "select",
      "label": "${t('plugins.schema_field_key_resolver')}",
      "options": [
        {"label": "${t('plugins.schema_option_ip')}", "value": "IP"},
        {"label": "${t('plugins.schema_option_user')}", "value": "USER"}
      ]
    }
  ]
}`);

function handleViewExample() {
  message.info(t('plugins.view_example_hint'));
}
</script>

<template>
  <div class="plugin-dev-guide">
    <a-alert
      class="plugin-dev-guide-alert plugin-info-alert"
      :message="t('plugins.development_guide')"
      :description="t('plugins.development_guide_desc')"
      type="info"
      show-icon
      closable
    />
    <a-card size="small" class="plugin-guide-card">
      <template #title>
        <span class="plugin-guide-title">
          <BookOutlined />{{ t('plugins.example_template') }}
        </span>
      </template>
      <template #extra>
        <a-button type="primary" @click="handleViewExample">
          <template #icon><DownloadOutlined /></template>
          {{ t('plugins.view_example_source') }}
        </a-button>
      </template>
      <a-collapse ghost>
        <a-collapse-panel key="steps" :header="t('plugins.dev_steps')">
          <ol class="plugin-guide-list">
            <li v-for="step in steps" :key="step">{{ step }}</li>
          </ol>
        </a-collapse-panel>
        <a-collapse-panel key="structure" :header="t('plugins.example_structure')">
          <pre class="plugin-guide-pre">plugin-example/
├── pom.xml
├── src/main/java/com/example/
│   └── CustomRateLimitGatewayFilterFactory.java
└── src/main/resources/META-INF/
    └── plugin-schema.json</pre>
        </a-collapse-panel>
        <a-collapse-panel key="schema" :header="t('plugins.schema_example')">
          <pre class="plugin-guide-pre plugin-guide-pre-scroll">{{ schemaExample }}</pre>
        </a-collapse-panel>
      </a-collapse>
    </a-card>
  </div>
</template>