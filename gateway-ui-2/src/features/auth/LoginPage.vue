<script setup lang="ts">
/**
 * Login page — visual port of the legacy LoginPage.tsx. Auth goes through the
 * Pinia auth store instead of the React AuthContext.
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  ArrowsAltOutlined, ArrowRightOutlined, EyeOutlined,
  LockOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined,
} from '@ant-design/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { message } from '@/utils/feedback'
import GatewayFlowBackground from './GatewayFlowBackground.vue'
import './login.css'

const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

const loading = ref(false)
const formState = reactive({ username: '', password: '' })

const usernameRules = computed(() => [
  { required: true, message: t('login.username_required') },
  { min: 3, message: t('login.username_min_length') },
])
const passwordRules = computed(() => [
  { required: true, message: t('login.password_required') },
  { min: 6, message: t('login.password_min_length') },
])

// Pointer-tracked spotlight inside the card (CSS vars consumed by .login-card::after).
function onCardMouseMove(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  el.style.setProperty('--mx', `${e.clientX - rect.left}px`)
  el.style.setProperty('--my', `${e.clientY - rect.top}px`)
}

async function onFinish() {
  loading.value = true
  try {
    const user = await auth.login(formState.username, formState.password)
    message.success(t('login.success', { nickname: user.nickname }))
    setTimeout(() => router.replace('/'), 500)
  } catch (error) {
    const msg = error instanceof ApiError && error.message ? error.message : t('login.failed')
    message.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <!-- Premium animated background -->
    <div class="login-background">
      <div class="bg-aurora bg-aurora-1" />
      <div class="bg-aurora bg-aurora-2" />
      <div class="bg-aurora bg-aurora-3" />
      <div class="bg-grid" />
      <GatewayFlowBackground :curve-count="9" :packets-per-curve="3" :opacity="0.9" />
      <div class="bg-horizon" />
      <div class="bg-spotlight" />
      <div class="bg-grain" />
      <div class="bg-vignette" />
    </div>

    <!-- Login card - Glassmorphism -->
    <div class="login-card" @mousemove="onCardMouseMove">
      <span class="card-ring" aria-hidden="true" />
      <div class="login-brand">
        <h2 class="brand-title ant-typography">{{ t('login.title') }}</h2>
        <span class="brand-subtitle">{{ t('login.subtitle') }}</span>
        <div class="brand-chips">
          <span class="chip"><ThunderboltOutlined />{{ t('login.chip.ha') }}</span>
          <span class="chip"><EyeOutlined />{{ t('login.chip.observability') }}</span>
          <span class="chip"><SafetyCertificateOutlined />{{ t('login.chip.security') }}</span>
          <span class="chip"><ArrowsAltOutlined />{{ t('login.chip.elastic') }}</span>
        </div>
      </div>

      <div class="login-form-section">
        <a-form
          name="login"
          layout="vertical"
          :model="formState"
          autocomplete="off"
          class="login-form"
          @finish="onFinish"
        >
          <a-form-item name="username" :rules="usernameRules">
            <a-input
              v-model:value="formState.username"
              size="large"
              class="modern-input"
              :placeholder="t('login.username_placeholder')"
            >
              <template #prefix><UserOutlined class="input-icon" /></template>
            </a-input>
          </a-form-item>

          <a-form-item name="password" :rules="passwordRules">
            <a-input-password
              v-model:value="formState.password"
              size="large"
              class="modern-input"
              :placeholder="t('login.password_placeholder')"
            >
              <template #prefix><LockOutlined class="input-icon" /></template>
            </a-input-password>
          </a-form-item>

          <a-form-item class="form-button">
            <a-button
              type="primary"
              html-type="submit"
              :loading="loading"
              size="large"
              block
              class="login-button"
            >
              <span class="login-button-label">{{ t('login.button') }}</span>
              <ArrowRightOutlined class="login-button-arrow" />
            </a-button>
          </a-form-item>
        </a-form>
      </div>
    </div>

    <div class="login-page-footer">
      <span class="page-footer-copyright ant-typography">
        © {{ new Date().getFullYear() }} leoli. All rights reserved.
      </span>
    </div>
  </div>
</template>
