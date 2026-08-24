/**
 * Maps strategy type icon names (stored in strategy_types.icon) to
 * antd icon elements.
 */
import React from 'react';
import {
  ThunderboltOutlined, SafetyOutlined, ClockCircleOutlined, StopOutlined,
  KeyOutlined, SyncOutlined, GlobalOutlined, FileTextOutlined,
  SettingOutlined, CloudOutlined, SecurityScanOutlined, NumberOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';

const ICON_MAP: Record<string, React.ReactNode> = {
  ThunderboltOutlined: <ThunderboltOutlined />,
  SafetyOutlined: <SafetyOutlined />,
  ClockCircleOutlined: <ClockCircleOutlined />,
  StopOutlined: <StopOutlined />,
  KeyOutlined: <KeyOutlined />,
  SyncOutlined: <SyncOutlined />,
  GlobalOutlined: <GlobalOutlined />,
  FileTextOutlined: <FileTextOutlined />,
  SettingOutlined: <SettingOutlined />,
  CloudOutlined: <CloudOutlined />,
  SecurityScanOutlined: <SecurityScanOutlined />,
  NumberOutlined: <NumberOutlined />,
  PlayCircleOutlined: <PlayCircleOutlined />,
};

export function getStrategyTypeIcon(iconName?: string): React.ReactNode {
  return (iconName && ICON_MAP[iconName]) || <ThunderboltOutlined />;
}
