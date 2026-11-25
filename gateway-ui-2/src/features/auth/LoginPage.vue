<script setup lang="ts">
/**
 * Login page — faithful port of gateway-ui (React) LoginPage.tsx.
 * Auth goes through the Pinia auth store instead of the React AuthContext.
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, ArrowRightOutlined, AuditOutlined, BranchesOutlined, CheckOutlined, EyeOutlined,
  LockOutlined, PieChartOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined,
} from '@/components/icons'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { message } from '@/utils/feedback'
import GatewayLogo from '@/components/GatewayLogo.vue'
import LoginParticles from './LoginParticles.vue'
import './login.css'

const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

const loading = ref(false)
const capsLock = ref(false)
const formState = reactive({ username: '', password: '' })

const usernameRules = computed(() => [
  { required: true, message: t('login.username_required') },
  { min: 3, message: t('login.username_min_length') },
])
const passwordRules = computed(() => [
  { required: true, message: t('login.password_required') },
  { min: 6, message: t('login.password_min_length') },
])

function onPasswordKey(event: KeyboardEvent) {
  capsLock.value = event.getModifierState('CapsLock')
}

function onPasswordBlur() {
  capsLock.value = false
}

// Slide-to-unlock captcha: the login form stays inert until the handle is
// dragged (or keyboard-nudged) to the end of the track.
const SLIDE_HANDLE_WIDTH = 34
const SLIDE_GAP = 3
const slideRef = ref<HTMLDivElement | null>(null)
const unlocked = ref(false)
const dragging = ref(false)
const offset = ref(0)
const maxOffset = ref(0)
const progress = computed(() =>
  maxOffset.value > 0 ? Math.round((offset.value / maxOffset.value) * 100) : 0,
)
const fillWidth = computed(() =>
  unlocked.value ? '100%' : `${offset.value + SLIDE_HANDLE_WIDTH + SLIDE_GAP}px`,
)
let dragStartX = 0
let dragStartOffset = 0

function measureSlide() {
  const track = slideRef.value
  if (track) {
    maxOffset.value = Math.max(0, track.clientWidth - SLIDE_HANDLE_WIDTH - SLIDE_GAP * 2)
  }
}

function onSlideStart(event: PointerEvent) {
  if (unlocked.value) return
  dragging.value = true
  dragStartX = event.clientX
  dragStartOffset = offset.value
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}

function onSlideMove(event: PointerEvent) {
  if (!dragging.value) return
  const next = dragStartOffset + (event.clientX - dragStartX)
  offset.value = Math.min(maxOffset.value, Math.max(0, next))
}

function completeSlide() {
  measureSlide()
  unlocked.value = true
  dragging.value = false
  offset.value = maxOffset.value
}

function onSlideEnd() {
  if (!dragging.value) return
  dragging.value = false
  if (offset.value >= maxOffset.value * 0.85) {
    completeSlide()
  } else {
    offset.value = 0
  }
}

function onSlideKeydown(event: KeyboardEvent) {
  if (unlocked.value) return
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault()
    const step = event.shiftKey ? 36 : 14
    const delta = event.key === 'ArrowRight' ? step : -step
    offset.value = Math.min(maxOffset.value, Math.max(0, offset.value + delta))
    if (offset.value >= maxOffset.value) completeSlide()
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    completeSlide()
  }
}

onMounted(() => {
  measureSlide()
  window.addEventListener('resize', measureSlide)
})
onUnmounted(() => window.removeEventListener('resize', measureSlide))

async function onFinish() {
  if (!unlocked.value) {
    message.warning(t('login.slide_required'))
    return
  }
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
    <div class="login-background" aria-hidden="true">
      <div class="bg-grid" />
      <svg class="login-background-flow" viewBox="0 0 1440 900" preserveAspectRatio="none">
        <defs>
          <linearGradient id="login-flow-stroke" x1="0" y1="0" x2="1" y2="0">
            <stop stop-color="#6998ef" stop-opacity="0" />
            <stop offset="0.4" stop-color="#6998ef" stop-opacity="0.13" />
            <stop offset="1" stop-color="#a492ea" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g fill="none" stroke="url(#login-flow-stroke)" stroke-width="1">
          <path d="M-100 610 C320 650 470 340 800 450 S1160 680 1540 400" />
          <path d="M-100 420 C260 390 410 630 740 530 S1180 300 1540 420" />
          <path d="M-100 760 C350 680 520 500 800 530 S1170 610 1540 700" />
        </g>
      </svg>
      <LoginParticles />
    </div>

    <header class="login-header">
      <div class="login-wordmark">
        <span class="login-logo">
          <GatewayLogo :style="{ width: '25px', height: '25px' }" />
        </span>
        <span>
          API GATEWAY<span class="wordmark-caption">{{ t('login.subtitle') }}</span>
        </span>
      </div>
      <span class="login-header-tag">{{ t('login.header_tag') }}</span>
    </header>

    <main class="login-main">
      <section class="login-intro" aria-labelledby="login-hero-title">
        <span class="login-eyebrow">
          <span />
          {{ t('login.eyebrow') }}
        </span>
        <h1 id="login-hero-title">
          {{ t('login.hero_line1') }}
          <br />
          <span>{{ t('login.hero_line2') }}</span>
        </h1>
        <i18n-t keypath="login.description_rich" tag="p" scope="global" class="login-description">
          <template #core><span class="desc-core">{{ t('login.desc.core') }}</span></template>
          <template #flow><span class="desc-flow">{{ t('login.desc.flow') }}</span></template>
          <template #trust><span class="desc-trust">{{ t('login.desc.trust') }}</span></template>
        </i18n-t>
        <div class="login-topology" aria-hidden="true">
          <div class="topology-heading">
            <span>{{ t('login.topology_title') }}</span>
            <span class="topology-caption">TRAFFIC FLOW</span>
          </div>
          <div class="topology-flow">
            <div class="topology-sources">
              <span>REST API</span>
              <span>AI / LLM</span>
              <span>WebSocket</span>
            </div>
            <svg class="topology-lines" viewBox="0 0 460 150" preserveAspectRatio="none">
              <path d="M0 25 H40 Q65 25 65 50 V55 Q65 75 95 75 H365 Q395 75 395 55 V50 Q395 25 420 25 H460 M0 75 H460 M0 125 H40 Q65 125 65 100 V95 Q65 75 95 75 M365 75 Q395 75 395 95 V100 Q395 125 420 125 H460" />
            </svg>
            <div class="topology-core">
              <GatewayLogo :style="{ width: '28px', height: '28px' }" />
              <span>GATEWAY</span>
            </div>
            <div class="topology-services">
              <span>{{ t('login.topology.microservices') }}</span>
              <span>{{ t('login.topology.models') }}</span>
              <span>{{ t('login.topology.apis') }}</span>
            </div>
          </div>
        </div>
        <div class="login-capabilities">
          <span>
            <ApiOutlined />
            {{ t('login.chip.microservices') }}
          </span>
          <span>
            <ThunderboltOutlined />
            {{ t('login.chip.ai') }}
          </span>
          <span>
            <EyeOutlined />
            {{ t('login.chip.observability') }}
          </span>
        </div>
        <div class="login-value-row">
          <div class="value-item">
            <BranchesOutlined class="value-icon" />
            <span class="value-copy">
              <span class="value-title">{{ t('login.sell.release_title') }}</span>
              <span class="value-note">{{ t('login.sell.release_note') }}</span>
            </span>
          </div>
          <div class="value-item">
            <PieChartOutlined class="value-icon" />
            <span class="value-copy">
              <span class="value-title">{{ t('login.sell.quota_title') }}</span>
              <span class="value-note">{{ t('login.sell.quota_note') }}</span>
            </span>
          </div>
          <div class="value-item">
            <AuditOutlined class="value-icon" />
            <span class="value-copy">
              <span class="value-title">{{ t('login.sell.audit_title') }}</span>
              <span class="value-note">{{ t('login.sell.audit_note') }}</span>
            </span>
          </div>
        </div>
      </section>

      <section class="login-card" aria-labelledby="login-form-title">
        <div class="login-brand">
          <div class="login-card-heading">
            <span class="login-card-icon" aria-hidden="true">
              <SafetyCertificateOutlined />
            </span>
            <span class="login-card-eyebrow">{{ t('login.card_eyebrow') }}</span>
            <span class="login-card-badge">{{ t('login.identity_badge') }}</span>
          </div>
          <a-typography-title :level="2" id="login-form-title" class="brand-title">
            {{ t('login.welcome') }}
          </a-typography-title>
          <span class="brand-subtitle">{{ t('login.form_description') }}</span>
        </div>

        <div class="login-form-section">
          <div class="login-form-divider">
            <span>{{ t('login.account_login') }}</span>
          </div>
          <a-form
            name="login"
            layout="vertical"
            :model="formState"
            autocomplete="on"
            :required-mark="false"
            class="login-form"
            @finish="onFinish"
          >
            <a-form-item
              name="username"
              :label="t('login.username_label')"
              :rules="usernameRules"
            >
              <a-input
                v-model:value="formState.username"
                size="large"
                class="modern-input"
                :placeholder="t('login.username_placeholder')"
                autocomplete="username"
                autocapitalize="none"
                :spellcheck="false"
              >
                <template #prefix><UserOutlined class="input-icon" /></template>
              </a-input>
            </a-form-item>

            <a-form-item
              name="password"
              :label="t('login.password_label')"
              :rules="passwordRules"
            >
              <a-input-password
                v-model:value="formState.password"
                size="large"
                class="modern-input"
                :placeholder="t('login.password_placeholder')"
                autocomplete="current-password"
                @keydown="onPasswordKey"
                @keyup="onPasswordKey"
                @blur="onPasswordBlur"
              >
                <template #prefix><LockOutlined class="input-icon" /></template>
              </a-input-password>
            </a-form-item>
            <p v-if="capsLock" class="login-caps-hint" role="status">
              {{ t('login.caps_lock') }}
            </p>

            <a-form-item class="form-slider">
              <div
                ref="slideRef"
                class="login-slide"
                :class="{ 'is-dragging': dragging, 'is-unlocked': unlocked }"
              >
                <span class="login-slide-fill" :style="{ width: fillWidth }" />
                <span class="login-slide-text">
                  {{ unlocked ? t('login.slide_done') : t('login.slide_hint') }}
                </span>
                <span
                  class="login-slide-handle"
                  role="slider"
                  :aria-valuemin="0"
                  :aria-valuemax="100"
                  :aria-valuenow="progress"
                  :aria-disabled="unlocked"
                  :aria-label="t('login.slide_hint')"
                  tabindex="0"
                  :style="{ transform: `translate3d(${offset}px, 0, 0)` }"
                  @pointerdown="onSlideStart"
                  @pointermove="onSlideMove"
                  @pointerup="onSlideEnd"
                  @pointercancel="onSlideEnd"
                  @keydown="onSlideKeydown"
                >
                  <CheckOutlined v-if="unlocked" />
                  <ArrowRightOutlined v-else />
                </span>
              </div>
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
        <p class="login-access-note">
          <span class="login-note-icon" aria-hidden="true">
            <LockOutlined />
          </span>
          <span>
            <strong>{{ t('login.access_title') }}</strong>
            {{ t('login.access_note') }}
          </span>
        </p>
      </section>
    </main>

    <footer class="login-page-footer">
      <a-typography-text class="page-footer-copyright">
        © {{ new Date().getFullYear() }} leoli. All rights reserved.
      </a-typography-text>
      <span class="login-footer-note">{{ t('login.footer_note') }}</span>
    </footer>
  </div>
</template>