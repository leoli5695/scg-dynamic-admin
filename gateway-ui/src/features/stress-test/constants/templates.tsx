import React from 'react';
import {
  AlertOutlined,
  ApiOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  FieldTimeOutlined,
  RocketOutlined,
  SafetyOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';

/** Template load profile applied to the create form. */
export interface StressTestTemplate {
  key: string;
  nameKey: string;
  icon: React.ReactNode;
  descriptionKey: string;
  scenarioKey: string;
  config: {
    concurrentUsers: number;
    totalRequests?: number;
    durationSeconds?: number;
    rampUpSeconds: number;
    targetQps?: number;
    requestTimeoutSeconds: number;
  };
}

/** Preset test templates for common scenarios. */
export const TEST_TEMPLATES: StressTestTemplate[] = [
  {
    key: 'smoke',
    nameKey: 'stress_test.template_smoke',
    icon: <ExperimentOutlined />,
    descriptionKey: 'stress_test.template_smoke_desc',
    scenarioKey: 'stress_test.template_smoke_scenario',
    config: {
      concurrentUsers: 5,
      totalRequests: 100,
      durationSeconds: undefined,
      rampUpSeconds: 5,
      targetQps: undefined,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'load',
    nameKey: 'stress_test.template_load',
    icon: <DashboardOutlined />,
    descriptionKey: 'stress_test.template_load_desc',
    scenarioKey: 'stress_test.template_load_scenario',
    config: {
      concurrentUsers: 50,
      totalRequests: undefined,
      durationSeconds: 300,
      rampUpSeconds: 30,
      targetQps: 500,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'stress',
    nameKey: 'stress_test.template_stress',
    icon: <ThunderboltOutlined />,
    descriptionKey: 'stress_test.template_stress_desc',
    scenarioKey: 'stress_test.template_stress_scenario',
    config: {
      concurrentUsers: 200,
      totalRequests: undefined,
      durationSeconds: 600,
      rampUpSeconds: 60,
      targetQps: 2000,
      requestTimeoutSeconds: 60,
    },
  },
  {
    key: 'spike',
    nameKey: 'stress_test.template_spike',
    icon: <RocketOutlined />,
    descriptionKey: 'stress_test.template_spike_desc',
    scenarioKey: 'stress_test.template_spike_scenario',
    config: {
      concurrentUsers: 500,
      totalRequests: undefined,
      durationSeconds: 120,
      rampUpSeconds: 10,
      targetQps: 5000,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'soak',
    nameKey: 'stress_test.template_soak',
    icon: <ClockCircleOutlined />,
    descriptionKey: 'stress_test.template_soak_desc',
    scenarioKey: 'stress_test.template_soak_scenario',
    config: {
      concurrentUsers: 30,
      totalRequests: undefined,
      durationSeconds: 1800,
      rampUpSeconds: 60,
      targetQps: 300,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'breakpoint',
    nameKey: 'stress_test.template_breakpoint',
    icon: <AlertOutlined />,
    descriptionKey: 'stress_test.template_breakpoint_desc',
    scenarioKey: 'stress_test.template_breakpoint_scenario',
    config: {
      concurrentUsers: 100,
      totalRequests: undefined,
      durationSeconds: 600,
      rampUpSeconds: 120,
      targetQps: undefined,
      requestTimeoutSeconds: 60,
    },
  },
  {
    key: 'latency',
    nameKey: 'stress_test.template_latency',
    icon: <FieldTimeOutlined />,
    descriptionKey: 'stress_test.template_latency_desc',
    scenarioKey: 'stress_test.template_latency_scenario',
    config: {
      concurrentUsers: 20,
      totalRequests: undefined,
      durationSeconds: 300,
      rampUpSeconds: 30,
      targetQps: 100,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'api_benchmark',
    nameKey: 'stress_test.template_api_benchmark',
    icon: <ApiOutlined />,
    descriptionKey: 'stress_test.template_api_benchmark_desc',
    scenarioKey: 'stress_test.template_api_benchmark_scenario',
    config: {
      concurrentUsers: 10,
      totalRequests: 10000,
      durationSeconds: undefined,
      rampUpSeconds: 10,
      targetQps: 1000,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'capacity',
    nameKey: 'stress_test.template_capacity',
    icon: <DatabaseOutlined />,
    descriptionKey: 'stress_test.template_capacity_desc',
    scenarioKey: 'stress_test.template_capacity_scenario',
    config: {
      concurrentUsers: 150,
      totalRequests: undefined,
      durationSeconds: 900,
      rampUpSeconds: 90,
      targetQps: 1500,
      requestTimeoutSeconds: 45,
    },
  },
  {
    key: 'stability',
    nameKey: 'stress_test.template_stability',
    icon: <SafetyOutlined />,
    descriptionKey: 'stress_test.template_stability_desc',
    scenarioKey: 'stress_test.template_stability_scenario',
    config: {
      concurrentUsers: 40,
      totalRequests: undefined,
      durationSeconds: 3600,
      rampUpSeconds: 120,
      targetQps: 400,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: 'gradual',
    nameKey: 'stress_test.template_gradual',
    icon: <SyncOutlined />,
    descriptionKey: 'stress_test.template_gradual_desc',
    scenarioKey: 'stress_test.template_gradual_scenario',
    config: {
      concurrentUsers: 80,
      totalRequests: undefined,
      durationSeconds: 480,
      rampUpSeconds: 240,
      targetQps: 800,
      requestTimeoutSeconds: 30,
    },
  },
];
