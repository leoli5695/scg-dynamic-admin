<script setup lang="ts">
/**
 * Maps strategy type icon names (stored in strategy_types.icon) to
 * ant-design-vue icon components.
 */
import { computed, type Component } from 'vue'
import {
  ClockCircleOutlined, CloudOutlined, ControlOutlined, FileTextOutlined,
  GlobalOutlined, KeyOutlined, NumberOutlined, PlayCircleOutlined,
  SafetyOutlined, SecurityScanOutlined, SettingOutlined, StopOutlined,
  SyncOutlined, ThunderboltOutlined,
} from '@/components/icons'

const props = defineProps<{ name?: string }>()

const ICON_MAP: Record<string, Component> = {
  ThunderboltOutlined,
  SafetyOutlined,
  ClockCircleOutlined,
  StopOutlined,
  KeyOutlined,
  SyncOutlined,
  GlobalOutlined,
  FileTextOutlined,
  SettingOutlined,
  CloudOutlined,
  SecurityScanOutlined,
  NumberOutlined,
  PlayCircleOutlined,
}

const icon = computed<Component>(() => (props.name && ICON_MAP[props.name]) || ControlOutlined)
</script>

<template>
  <component :is="icon" />
</template>