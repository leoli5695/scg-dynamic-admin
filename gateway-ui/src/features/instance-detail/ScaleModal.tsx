/**
 * Scale modal — replicas and/or spec modification.
 * Request body aligned with dto/ScaleInstanceRequest.java.
 */
import React, { useState } from 'react';
import {
  Form, InputNumber, Modal, Select, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import { useTranslation } from 'react-i18next';
import { scaleInstance } from '../../api/instances';
import { ApiError } from '../../api/http';
import type { GatewayInstance, ScaleInstanceRequest } from '../../types/instance';
import { getSpecText } from '../../utils/instance';

const { Text } = Typography;

/** Preset spec values (must match backend InstanceSpec enum). */
const SPEC_VALUES: Record<string, { cpu: number; memory: number }> = {
  small: { cpu: 0.5, memory: 512 },
  medium: { cpu: 1, memory: 1024 },
  large: { cpu: 2, memory: 2048 },
  xlarge: { cpu: 4, memory: 4096 },
};

interface ScaleModalProps {
  open: boolean;
  instance: GatewayInstance;
  onClose: () => void;
  onScaled: () => void;
}

/**
 * Scale modal body. Mounted only while open (parent renders it conditionally),
 * so initial state comes straight from the instance on each open.
 */
const ScaleModal: React.FC<ScaleModalProps> = ({
  open, instance, onClose, onScaled,
}) => {
  const { t } = useTranslation();
  const [replicas, setReplicas] = useState(instance.replicas || 1);
  const [specType, setSpecType] = useState('');
  const [cpuCores, setCpuCores] = useState(instance.cpuCores || 1);
  const [memoryMB, setMemoryMB] = useState(instance.memoryMB || 1024);
  const [loading, setLoading] = useState(false);

  const specOptions = [
    { value: '', label: t('instance.spec_no_change') || '不修改规格' },
    { value: 'small', label: t('instance.spec_small') || '小型 (0.5C 512MB)' },
    { value: 'medium', label: t('instance.spec_medium') || '中型 (1C 1024MB)' },
    { value: 'large', label: t('instance.spec_large') || '大型 (2C 2048MB)' },
    { value: 'xlarge', label: t('instance.spec_xlarge') || '超大型 (4C 4096MB)' },
    { value: 'custom', label: t('instance.spec_custom') || '自定义' },
  ];

  const handleSpecChange = (value: string) => {
    setSpecType(value);
    if (value === 'custom') {
      setCpuCores(instance.cpuCores || 1);
      setMemoryMB(instance.memoryMB || 1024);
    } else if (value && SPEC_VALUES[value]) {
      setCpuCores(SPEC_VALUES[value].cpu);
      setMemoryMB(SPEC_VALUES[value].memory);
    }
  };

  const handleOk = async () => {
    setLoading(true);
    try {
      const body: ScaleInstanceRequest = { replicas };
      if (specType) {
        body.specType = specType;
        if (specType === 'custom') {
          body.cpuCores = cpuCores;
          body.memoryMB = memoryMB;
        }
      }
      await scaleInstance(instance.id, body);
      const successMsg = specType && specType !== instance.specType
        ? (t('instance.scale_and_spec_success') || '扩缩容和规格修改成功，Pod正在重启')
        : t('instance.scale_success');
      message.success(successMsg);
      onClose();
      onScaled();
    } catch (error) {
      const msg = error instanceof ApiError && error.message
        ? error.message
        : t('instance.scale_failed');
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={t('instance.scale_title') || '扩缩容与规格修改'}
      open={open}
      onOk={handleOk}
      onCancel={onClose}
      confirmLoading={loading}
      okText={t('instance.scale_confirm') || '确认'}
      cancelText={t('common.cancel') || '取消'}
      width={500}
    >
      <Form layout="vertical">
        <Form.Item label={t('instance.current_replicas') || '当前副本数'}>
          <Text>
            {instance.replicas || 0}
            {' '}
            Pods
          </Text>
        </Form.Item>
        <Form.Item label={t('instance.target_replicas') || '目标副本数'} required>
          <InputNumber
            min={1}
            max={10}
            value={replicas}
            onChange={(value) => setReplicas(value || 1)}
            style={{ width: '100%' }}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('instance.replicas_hint') || '支持 1-10 个副本，修改副本数不会重启 Pod'}
          </Text>
        </Form.Item>

        <Form.Item label={t('instance.current_spec') || '当前规格'}>
          <Text>
            {getSpecText(instance, t)}
            {' '}
            (
            {instance.cpuCores || 0}
            C
            {' '}
            {instance.memoryMB || 0}
            MB)
          </Text>
        </Form.Item>
        <Form.Item label={t('instance.target_spec') || '目标规格'}>
          <Select
            value={specType}
            onChange={handleSpecChange}
            options={specOptions}
            style={{ width: '100%' }}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('instance.spec_hint') || '修改规格会触发 Pod 重启'}
          </Text>
        </Form.Item>

        {specType === 'custom' && (
          <>
            <Form.Item label={t('instance.cpu_cores') || 'CPU 核数'} required>
              <InputNumber
                min={0.5}
                max={8}
                step={0.5}
                value={cpuCores}
                onChange={(value) => setCpuCores(value || 1)}
                style={{ width: '100%' }}
                suffix="核"
              />
            </Form.Item>
            <Form.Item label={t('instance.memory_mb') || '内存大小'} required>
              <InputNumber
                min={256}
                max={8192}
                step={256}
                value={memoryMB}
                onChange={(value) => setMemoryMB(value || 1024)}
                style={{ width: '100%' }}
                suffix="MB"
              />
            </Form.Item>
          </>
        )}

        {specType !== '' && specType !== instance.specType && (
          <div style={{
            padding: '8px 12px',
            background: '#fffbe6',
            border: '1px solid #ffe58f',
            borderRadius: 4,
            marginTop: 8,
          }}
          >
            <Text type="warning">
              ⚠️
              {' '}
              {t('instance.spec_change_warning') || '规格修改将触发 Pod 重启，服务可能会有短暂中断'}
            </Text>
          </div>
        )}
      </Form>
    </Modal>
  );
};

export default ScaleModal;
