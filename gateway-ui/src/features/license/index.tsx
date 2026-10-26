import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, Descriptions, Divider, Empty, Progress, Row, Space, Spin,
  Statistic, Table, Tabs, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import { parseServerTime } from '../../utils/serverTime';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined, CloseCircleOutlined, CloudOutlined,
  GlobalOutlined, KeyOutlined, ReloadOutlined, SafetyCertificateOutlined, SettingOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getLicenseList, getLicenseStatus } from '../../api/license';
import logger from '../../utils/logger';
import type { LicenseEdition } from '../../types/license';
import type { LicenseListItem, LicenseStatus } from '../../types/license';
import {
  EDITION_CONFIG, FEATURE_CATALOG, STATUS_CONFIG,
  type LicenseFeatureDef, type LicenseStatusCode,
} from './constants/featureCatalog';
import { ActivateLicenseModal } from './components/ActivateLicenseModal';
import { FeatureDetailDrawer } from './components/FeatureDetailDrawer';
import { FeatureMatrix } from './components/FeatureMatrix';
import './license.css';

const { Text, Title } = Typography;

const LicensePage: React.FC = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  // Null when no license is loaded (backend loaded=false / UNLICENSED).
  const [licenseInfo, setLicenseInfo] = useState<LicenseStatus | null>(null);
  const [licenseList, setLicenseList] = useState<LicenseListItem[]>([]);
  const [features, setFeatures] = useState<LicenseFeatureDef[]>(FEATURE_CATALOG);
  const [activeTab, setActiveTab] = useState('status');
  const [activateModalVisible, setActivateModalVisible] = useState(false);
  const [selectedFeature, setSelectedFeature] = useState<LicenseFeatureDef | null>(null);

  const updateFeatureStatus = useCallback((enabledFeatures: string[]) => {
    setFeatures(FEATURE_CATALOG.map(f => ({
      ...f,
      enabled: enabledFeatures.includes(f.name),
    })));
  }, []);

  const fetchLicenseData = useCallback(async () => {
    setLoading(true);
    try {
      const status = await getLicenseStatus();
      setLicenseInfo(status.loaded ? status : null);
      updateFeatureStatus(status.features || []);

      // Single imported license; may be unavailable for non-admin users.
      try {
        const list = await getLicenseList();
        setLicenseList(list || []);
      } catch {
        logger.debug('License list API not available');
      }
    } catch (error) {
      logger.error('Failed to fetch license data', error);
      message.error(t('common.fetch_error'));
    } finally {
      setLoading(false);
    }
  }, [t, updateFeatureStatus]);

  useEffect(() => {
    (async () => {
      await fetchLicenseData();
    })();
  }, [fetchLicenseData]);

  const editionConfig = licenseInfo
    ? EDITION_CONFIG[licenseInfo.edition as LicenseEdition] ?? EDITION_CONFIG.BASIC
    : EDITION_CONFIG.BASIC;

  const licenseColumns: ColumnsType<LicenseListItem> = [
    {
      title: 'License ID',
      dataIndex: 'licenseId',
      key: 'licenseId',
      width: 180,
      render: (id: string) => (
        <Tooltip title={id}>
          <Text copyable={{ text: id }} style={{ fontSize: 12 }}>
            {id.substring(0, 8)}...
          </Text>
        </Tooltip>
      ),
    },
    {
      title: '版本',
      dataIndex: 'edition',
      key: 'edition',
      width: 100,
      render: (edition: string) => {
        const config = EDITION_CONFIG[edition as LicenseEdition] ?? EDITION_CONFIG.BASIC;
        return (
          <Tag color={config.color} icon={config.icon}>
            {config.label}
          </Tag>
        );
      },
    },
    {
      title: '客户',
      dataIndex: 'customerName',
      key: 'customerName',
      width: 150,
      render: (name: string, record) => (
        <Tooltip title={record.customerEmail}>
          <Text>{name}</Text>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => {
        const config = STATUS_CONFIG[status as LicenseStatusCode] ?? STATUS_CONFIG.ACTIVE;
        return (
          <Badge
            status={config.color as 'success' | 'error'}
            text={status}
          />
        );
      },
    },
    {
      title: '有效期',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 180,
      render: (date: string | null) => {
        if (!date) return <Text>永久</Text>;
        const expired = parseServerTime(date).getTime() < Date.now();
        return (
          <Text type={expired ? 'danger' : undefined}>
            {parseServerTime(date).toLocaleDateString()}
            {expired && <CloseCircleOutlined style={{ marginLeft: 4 }} />}
          </Text>
        );
      },
    },
    {
      title: '功能数',
      dataIndex: 'features',
      key: 'features',
      width: 80,
      render: (featureList: string[] | undefined) => <Tag>{featureList?.length || 0}</Tag>,
    },
  ];

  return (
    <div className="license-page">
      <Spin spinning={loading}>
        {/* Page Header */}
        <div className="license-page-header">
          <div className="license-page-title">
            <KeyOutlined />
            <Title level={4} style={{ margin: 0 }}>
              License 授权管理
            </Title>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={fetchLicenseData}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<KeyOutlined />}
              onClick={() => setActivateModalVisible(true)}
            >
              激活 License
            </Button>
          </Space>
        </div>

        {/* License Status Overview */}
        {!licenseInfo && (
          <Alert
            type="warning"
            message="未检测到有效 License"
            description="请激活 License 以使用高级功能。基础版功能可免费使用。"
            showIcon
            style={{ marginBottom: 16 }}
          />
        )}

        {licenseInfo && licenseInfo.expired && (
          <Alert
            type="error"
            message="License 已过期"
            description={`您的 License 于 ${licenseInfo.expiresAt ? new Date(licenseInfo.expiresAt).toLocaleDateString() : '-'} 过期，部分功能已受限。请联系管理员续期。`}
            showIcon
            style={{ marginBottom: 16 }}
          />
        )}

        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'status',
              label: '授权状态',
              icon: <SafetyCertificateOutlined />,
              children: (
                <div className="license-tab-content">
                  {/* Current License Card */}
                  <Row gutter={[16, 16]}>
                    <Col xs={24} lg={8}>
                      <Card className="license-status-card">
                        <div className="license-edition-header">
                          {licenseInfo ? (
                            <>
                              <div className="edition-icon">
                                {editionConfig.icon}
                              </div>
                              <Title level={3}>
                                {editionConfig.label}
                              </Title>
                              <Tag color={licenseInfo.valid ? 'success' : 'error'}>
                                {licenseInfo.valid ? '有效' : '无效'}
                              </Tag>
                            </>
                          ) : (
                            <>
                              <div className="edition-icon">
                                <KeyOutlined />
                              </div>
                              <Title level={3}>基础版</Title>
                              <Tag>免费</Tag>
                            </>
                          )}
                        </div>
                        {licenseInfo?.daysRemaining != null && (
                          <div className="license-expiry">
                            <Progress
                              percent={
                                Math.max(
                                  0,
                                  Math.min(
                                    100,
                                    (licenseInfo.daysRemaining / 365) * 100,
                                  ),
                                )
                              }
                              status={
                                licenseInfo.daysRemaining < 30
                                  ? 'exception'
                                  : 'active'
                              }
                              format={() => `剩余 ${licenseInfo.daysRemaining} 天`}
                            />
                          </div>
                        )}
                      </Card>
                    </Col>

                    <Col xs={24} lg={16}>
                      {/* Quota Overview */}
                      <Card title="配额使用" className="quota-card">
                        <Row gutter={16}>
                          <Col span={6}>
                            <Statistic
                              title="实例数"
                              value={licenseInfo?.quotas?.maxInstances || 1}
                              suffix={`/ ${licenseInfo?.quotas?.maxInstances || 1}`}
                              prefix={<CloudOutlined />}
                            />
                          </Col>
                          <Col span={6}>
                            <Statistic
                              title="路由数"
                              value={licenseInfo?.quotas?.maxRoutes || 100}
                              suffix={`/ ${licenseInfo?.quotas?.maxRoutes || 100}`}
                              prefix={<ApiOutlined />}
                            />
                          </Col>
                          <Col span={6}>
                            <Statistic
                              title="服务数"
                              value={licenseInfo?.quotas?.maxServices || 50}
                              suffix={`/ ${licenseInfo?.quotas?.maxServices || 50}`}
                              prefix={<GlobalOutlined />}
                            />
                          </Col>
                          <Col span={6}>
                            <Statistic
                              title="限流 QPS"
                              value={licenseInfo?.quotas?.maxRateLimitQPS || 100}
                              suffix={`/ ${licenseInfo?.quotas?.maxRateLimitQPS || 100}`}
                              prefix={<ThunderboltOutlined />}
                            />
                          </Col>
                        </Row>
                        <Divider className="quota-divider" />
                        <Descriptions column={{ xs: 2, md: 4 }} size="small" className="quota-descriptions">
                          {licenseInfo && (
                            <>
                              <Descriptions.Item label="License ID">
                                <Text copyable style={{ fontSize: 12 }}>
                                  {licenseInfo.licenseId?.substring(0, 16)}...
                                </Text>
                              </Descriptions.Item>
                              <Descriptions.Item label="客户">
                                {licenseInfo.customerName || '-'}
                              </Descriptions.Item>
                              <Descriptions.Item label="组织">
                                {licenseInfo.organization || '-'}
                              </Descriptions.Item>
                              <Descriptions.Item label="有效期至">
                                {licenseInfo.expiresAt ? parseServerTime(licenseInfo.expiresAt).toLocaleDateString() : '永久'}
                              </Descriptions.Item>
                            </>
                          )}
                        </Descriptions>
                      </Card>
                    </Col>
                  </Row>

                  {/* Feature Matrix */}
                  <FeatureMatrix
                    features={features}
                    onFeatureClick={setSelectedFeature}
                  />
                </div>
              ),
            },
            {
              key: 'manage',
              label: 'License 管理',
              icon: <SettingOutlined />,
              children: (
                <div className="license-tab-content">
                  <Card title="License 列表">
                    <Table
                      dataSource={licenseList}
                      columns={licenseColumns}
                      rowKey="licenseId"
                      pagination={{
                        pageSize: 10,
                        showSizeChanger: true,
                        showTotal: (total) => `共 ${total} 条`,
                      }}
                      scroll={{ x: 1000 }}
                      locale={{
                        emptyText: (
                          <Empty description="暂无 License 记录" />
                        ),
                      }}
                    />
                  </Card>
                </div>
              ),
            },
          ]}
        />
      </Spin>

      <ActivateLicenseModal
        open={activateModalVisible}
        onClose={() => setActivateModalVisible(false)}
        onActivated={fetchLicenseData}
      />

      <FeatureDetailDrawer
        feature={selectedFeature}
        onClose={() => setSelectedFeature(null)}
      />
    </div>
  );
};

export default LicensePage;
