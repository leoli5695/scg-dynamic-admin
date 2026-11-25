/**
 * Status/score render helpers shared by the diagnostic feature.
 * Vue port of gateway-ui/src/features/diagnostic/utils.tsx — icons become
 * components and colours become shared login-skin tokens.
 */
import type { Component } from 'vue'
import {
  CheckCircleOutlined, CloseCircleOutlined, QuestionCircleOutlined, WarningOutlined,
} from '@/components/icons'

export type StatusTone = 'success' | 'warning' | 'error' | 'neutral'

/** Component/report status → capsule badge tone. */
export function getStatusTone(status: string): StatusTone {
  switch (status) {
    case 'HEALTHY': return 'success'
    case 'WARNING': return 'warning'
    case 'CRITICAL': return 'error'
    default: return 'neutral'
  }
}

/** Component/report status → icon component. */
export function getStatusIcon(status: string): Component {
  switch (status) {
    case 'HEALTHY': return CheckCircleOutlined
    case 'WARNING': return WarningOutlined
    case 'CRITICAL': return CloseCircleOutlined
    default: return QuestionCircleOutlined
  }
}

/** Health score → progress colour (shared Vue-native semantic tokens). */
export function getScoreColor(score: number): string {
  if (score >= 80) return 'var(--vn-ok)'
  if (score >= 50) return 'var(--vn-warn)'
  return 'var(--vn-err)'
}

/** Health score → antd progress status. */
export function getScoreStatus(score: number): 'success' | 'normal' | 'exception' {
  if (score >= 80) return 'success'
  if (score >= 50) return 'normal'
  return 'exception'
}