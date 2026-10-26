/**
 * Instance create page (rewritten).
 * Form fields aligned with dto/InstanceCreateRequest.java;
 * specs via GET /api/instances/specs, local images via /clusters/{id}/images.
 */
import React, { useMemo, useState } from 'react';
import {
  Alert, Button, Card, Col, Divider, Form, Input, InputNumber, Radio, Row,
  Select, Space, Spin, Tag  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  AppstoreOutlined, ArrowLeftOutlined, CloudServerOutlined,
  CloudSyncOutlined, ClusterOutlined, DatabaseOutlined, SettingOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { createInstance, listInstanceSpecs } from '../../api/instances';
import { getClusterImages, getClusters } from '../../api/kubernetes';
import { ApiError } from '../../api/http';
import type { InstanceCreateRequest } from '../../types/instance';
import '../../styles/form-page.css';

const { Option } = Select;
const { TextArea } = Input;

/** Parsed local image: [registry/][namespace/]image[:tag]. */
interface LocalImage {
  name: string;
  namespace: string;
  image: string;
  tag: string;
}

const parseImageName = (fullName: string): LocalImage => {
  const parts = fullName.split('/');
  let namespace = '';
  let imageWithTag = fullName;
  if (parts.length >= 2 && !parts[0].includes(':')) {
    namespace = parts[0];
    imageWithTag = parts.slice(1).join('/');
  }
  const [image, tag] = imageWithTag.split(':');
  return {
    name: fullName,
    namespace: namespace || 'default',
    image,
    tag: tag || 'latest',
  };
};

/**
 * Strip everything the backend would interpret as "deploy this to Kubernetes". An external
 * instance keeps only its identity and the middleware addresses it should be pointed at; the
 * absence of clusterId is what selects the external registration path server-side.
 */
const toExternalRequest = (values: InstanceCreateRequest): InstanceCreateRequest => ({
  instanceName: values.instanceName,
  description: values.description,
  nacosServerAddr: values.nacosServerAddr,
  redisServerAddr: values.redisServerAddr,
  jaegerServerAddr: values.jaegerServerAddr,
  prometheusServerAddr: values.prometheusServerAddr,
});

const InstanceCreatePage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [form] = Form.useForm<InstanceCreateRequest>();
  const [submitting, setSubmitting] = useState(false);
  /**
   * 'kubernetes' deploys a new gateway; 'external' only registers one the operator already
   * started. The backend decides by the presence of clusterId, so an external submit must not
   * carry any of the Kubernetes-only fields.
   */
  const [deployMode, setDeployMode] = useState<'kubernetes' | 'external'>('kubernetes');
  const isExternal = deployMode === 'external';

  const clusters = useRequest((signal) => getClusters({ signal }));
  const specs = useRequest((signal) => listInstanceSpecs({ signal }));

  const selectedSpecType = Form.useWatch('specType', form);
  const selectedClusterId = Form.useWatch('clusterId', form);
  const selectedImagePullPolicy = Form.useWatch('imagePullPolicy', form);

  // Local images (only relevant when pull policy is "Never")
  const needLocalImages = !isExternal && !!selectedClusterId && selectedImagePullPolicy === 'Never';
  const [selectedImageNamespace, setSelectedImageNamespace] = useState<string | undefined>(undefined);

  const imagesRequest = useRequest(
    (signal) => getClusterImages(selectedClusterId as number, { signal }),
    {
      immediate: needLocalImages,
      deps: [selectedClusterId, selectedImagePullPolicy],
      onSuccess: (raw) => {
        // Auto-select k8s.io namespace if available
        if ((raw ?? []).some((img) => parseImageName(img.name).namespace === 'k8s.io')) {
          setSelectedImageNamespace('k8s.io');
        }
      },
      onError: (error) => {
        if (error.status === 0) return;
        message.warning('Failed to load local images from cluster');
      },
    },
  );

  const localImages = useMemo<LocalImage[]>(
    () => (needLocalImages
      ? (imagesRequest.data ?? []).map((img) => parseImageName(img.name))
      : []),
    [needLocalImages, imagesRequest.data],
  );
  const loadingImages = needLocalImages && imagesRequest.loading;

  const filteredImages = selectedImageNamespace && selectedImageNamespace !== 'all'
    ? localImages.filter((img) => img.namespace === selectedImageNamespace)
    : localImages;

  const namespaces = useMemo(
    () => Array.from(new Set(localImages.map((img) => img.namespace))),
    [localImages],
  );

  const handleSubmit = async (values: InstanceCreateRequest) => {
    setSubmitting(true);
    try {
      const created = await createInstance(isExternal ? toExternalRequest(values) : values);
      message.success(t('instance.create_success'));
      // An external instance still has to be wired up by hand, so land on its detail page where
      // the generated GATEWAY_INSTANCE_ID / NACOS_NAMESPACE are shown instead of the list.
      navigate(created?.instanceId ? `/instances/${created.instanceId}` : '/instances');
    } catch (error) {
      const msg = error instanceof ApiError && error.message
        ? error.message
        : t('instance.create_failed');
      message.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => navigate('/instances');
  const loading = clusters.loading || specs.loading;

  return (
    <div className="form-page-container">
      <Card className="form-card">
        <div className="form-header">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={handleCancel}
            className="form-header-back"
          />
          <div className="form-header-icon">
            <CloudServerOutlined />
          </div>
          <h3 className="form-header-title">{t('instance.create_instance')}</h3>
        </div>

        <Divider style={{ margin: '20px 0 0', borderColor: 'rgba(255,255,255,0.08)' }} />

        <Spin spinning={loading}>
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{
              specType: 'medium',
              replicas: 1,
              createNamespace: true,
              image: 'my-gateway:latest',
              imagePullPolicy: 'Never',
              serverPort: 80,
              managementPort: 9091,
            }}
          >
            {/* Basic Info Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <AppstoreOutlined />
                </div>
                <span className="form-section-title">{t('instance.basic_info')}</span>
              </div>
              <div className="form-section-content">
                <Form.Item label={t('instance.deploy_mode')} style={{ marginBottom: 16 }}>
                  <Radio.Group
                    value={deployMode}
                    onChange={(e) => setDeployMode(e.target.value)}
                    optionType="button"
                    buttonStyle="solid"
                    size="large"
                  >
                    <Radio.Button value="kubernetes">
                      <Space>
                        <ClusterOutlined />
                        {t('instance.deploy_mode_kubernetes')}
                      </Space>
                    </Radio.Button>
                    <Radio.Button value="external">
                      <Space>
                        <CloudServerOutlined />
                        {t('instance.deploy_mode_external')}
                      </Space>
                    </Radio.Button>
                  </Radio.Group>
                </Form.Item>
                {isExternal && (
                  <Alert
                    message={t('instance.external_hint')}
                    type="warning"
                    showIcon
                    style={{ marginBottom: 16 }}
                  />
                )}
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="instanceName"
                      label={t('instance.instance_name')}
                      rules={[{ required: true, message: t('common.required') }]}
                    >
                      <Input placeholder={t('instance.instance_name_placeholder')} size="large" />
                    </Form.Item>
                  </Col>
                  {!isExternal && (
                    <Col span={12}>
                      <Form.Item
                        name="clusterId"
                        label={t('instance.cluster')}
                        rules={[{ required: true, message: t('common.required') }]}
                      >
                        <Select placeholder={t('instance.select_cluster')} size="large">
                          {(clusters.data ?? [])
                            .filter((c) => c.connectionStatus === 'CONNECTED')
                            .map((cluster) => (
                              <Option key={cluster.id} value={cluster.id}>
                                <Space>
                                  <ClusterOutlined style={{ color: '#60a5fa' }} />
                                  {cluster.clusterName}
                                  <Tag color="blue" style={{ marginLeft: 8 }}>{cluster.clusterVersion}</Tag>
                                </Space>
                              </Option>
                            ))}
                        </Select>
                      </Form.Item>
                    </Col>
                  )}
                </Row>
                {!isExternal && (
                  <Row gutter={16}>
                    <Col span={12}>
                      <Form.Item
                        name="namespace"
                        label={t('instance.namespace')}
                        rules={[{ required: true, message: t('common.required') }]}
                      >
                        <Input placeholder={t('instance.namespace_placeholder')} size="large" />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item name="createNamespace" label={t('instance.create_namespace')}>
                        <Select size="large">
                          <Option value>{t('common.yes')}</Option>
                          <Option value={false}>{t('common.no')}</Option>
                        </Select>
                      </Form.Item>
                    </Col>
                  </Row>
                )}
              </div>
            </div>

            {/* Configuration Section */}
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <DatabaseOutlined />
                </div>
                <span className="form-section-title">{t('instance.config_section')}</span>
                <span className="form-section-subtitle">{t('instance.config_section_hint')}</span>
              </div>
              <div className="form-section-content">
                <Alert
                  message={t('instance.config_hint')}
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="nacosServerAddr"
                      label={t('instance.nacos_server_addr')}
                      tooltip={t('instance.nacos_server_addr_tooltip')}
                    >
                      <Input placeholder={t('instance.nacos_server_addr_placeholder')} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="redisServerAddr"
                      label={t('instance.redis_server_addr')}
                      tooltip={t('instance.redis_server_addr_tooltip')}
                    >
                      <Input placeholder={t('instance.redis_server_addr_placeholder')} size="large" />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="jaegerServerAddr"
                      label={t('instance.jaeger_server_addr')}
                      tooltip={t('instance.jaeger_server_addr_tooltip')}
                    >
                      <Input placeholder={t('instance.jaeger_server_addr_placeholder')} size="large" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="prometheusServerAddr"
                      label={t('instance.prometheus_server_addr')}
                      tooltip={t('instance.prometheus_server_addr_tooltip')}
                    >
                      <Input placeholder={t('instance.prometheus_server_addr_placeholder')} size="large" />
                    </Form.Item>
                  </Col>
                </Row>
              </div>
            </div>

            {/* Resource Section — Kubernetes only: an external instance has no Deployment to size. */}
            {!isExternal && (
            <div className="form-section">
              <div className="form-section-header">
                <div className="form-section-icon">
                  <SettingOutlined />
                </div>
                <span className="form-section-title">{t('instance.resource_section')}</span>
              </div>
              <div className="form-section-content">
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item
                      name="specType"
                      label={t('instance.spec_type')}
                      rules={[{ required: true }]}
                    >
                      <Select size="large">
                        {(specs.data ?? []).map((spec) => (
                          <Option key={spec.type} value={spec.type}>
                            {spec.type === 'custom'
                              ? t('instance.spec_custom')
                              : t(`instance.spec_${spec.type}`)}
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  {selectedSpecType === 'custom' && (
                    <>
                      <Col span={8}>
                        <Form.Item
                          name="cpuCores"
                          label={t('instance.cpu_cores')}
                          rules={[{ required: true, message: t('common.required') }]}
                        >
                          <InputNumber
                            min={0.1}
                            max={16}
                            step={0.5}
                            size="large"
                            style={{ width: '100%' }}
                            placeholder="1.0"
                          />
                        </Form.Item>
                      </Col>
                      <Col span={8}>
                        <Form.Item
                          name="memoryMB"
                          label={t('instance.memory_mb')}
                          rules={[{ required: true, message: t('common.required') }]}
                        >
                          <InputNumber
                            min={128}
                            max={16384}
                            step={128}
                            size="large"
                            style={{ width: '100%' }}
                            placeholder="1024"
                          />
                        </Form.Item>
                      </Col>
                    </>
                  )}
                  <Col span={8}>
                    <Form.Item
                      name="replicas"
                      label={t('instance.replicas')}
                      rules={[{ required: true }]}
                    >
                      <InputNumber min={1} max={10} size="large" style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                </Row>
              </div>
            </div>
            )}

            {/* Image Section — Kubernetes only; the description below it applies to both modes. */}
            <div className="form-section">
              {!isExternal && (
                <div className="form-section-header">
                  <div className="form-section-icon">
                    <CloudSyncOutlined />
                  </div>
                  <span className="form-section-title">{t('instance.image_section')}</span>
                </div>
              )}
              <div className="form-section-content">
                {!isExternal && (
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="imagePullPolicy"
                      label={t('instance.image_pull_policy')}
                      tooltip={t('instance.image_pull_policy_tooltip')}
                    >
                      <Select size="large" style={{ width: '100%' }}>
                        <Option value="IfNotPresent">{t('instance.pull_if_not_present')}</Option>
                        <Option value="Always">{t('instance.pull_always')}</Option>
                        <Option value="Never">{t('instance.pull_never')}</Option>
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="image"
                      label={t('instance.image')}
                      tooltip={
                        selectedImagePullPolicy === 'Never'
                          ? 'Select from local images available on cluster nodes'
                          : t('instance.image_tooltip')
                      }
                    >
                      {selectedImagePullPolicy === 'Never' ? (
                        loadingImages ? (
                          <Spin tip="Loading images..." />
                        ) : localImages.length > 0 ? (
                          <Space direction="vertical" style={{ width: '100%' }} size="small">
                            <Select
                              value={selectedImageNamespace || 'all'}
                              onChange={setSelectedImageNamespace}
                              size="large"
                              style={{ width: '100%' }}
                              placeholder="Filter by namespace"
                            >
                              <Option value="all">
                                All Namespaces (
                                {localImages.length}
                                )
                              </Option>
                              {namespaces.map((ns) => {
                                const count = localImages.filter((img) => img.namespace === ns).length;
                                return (
                                  <Option key={ns} value={ns}>
                                    {ns}
                                    {' '}
                                    (
                                    {count}
                                    )
                                  </Option>
                                );
                              })}
                            </Select>
                            <Select
                              showSearch
                              size="large"
                              placeholder={selectedImageNamespace ? `Select from ${selectedImageNamespace}` : 'Select local image'}
                              optionFilterProp="children"
                              loading={loadingImages}
                              allowClear
                              style={{ width: '100%' }}
                              optionLabelProp="title"
                            >
                              {filteredImages.map((img) => (
                                <Option key={img.name} value={img.name} title={img.name}>
                                  <Space>
                                    <Tag color="blue">{img.namespace}</Tag>
                                    <span style={{ color: '#fafafa' }}>{img.image}</span>
                                    <Tag color="green">{img.tag}</Tag>
                                  </Space>
                                </Option>
                              ))}
                            </Select>
                          </Space>
                        ) : (
                          <Input
                            size="large"
                            placeholder="No local images found. Enter manually."
                            style={{ width: '100%' }}
                          />
                        )
                      ) : (
                        <Input
                          size="large"
                          placeholder="e.g., my-gateway:latest"
                          style={{ width: '100%' }}
                        />
                      )}
                    </Form.Item>
                  </Col>
                </Row>
                )}
                <Form.Item name="description" label={t('instance.description')}>
                  <TextArea rows={3} size="large" showCount maxLength={500} />
                </Form.Item>
              </div>
            </div>

            {/* Footer */}
            <div className="form-footer">
              <Button size="large" onClick={handleCancel}>{t('common.cancel')}</Button>
              <Button type="primary" size="large" htmlType="submit" loading={submitting}>
                {isExternal ? t('instance.register_external') : t('instance.create_and_deploy')}
              </Button>
            </div>
          </Form>
        </Spin>
      </Card>
    </div>
  );
};

export default InstanceCreatePage;
