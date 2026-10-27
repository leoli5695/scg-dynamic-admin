import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Empty,
  Row,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { message } from '../../utils/antdStatic';
import { parseServerTime } from '../../utils/serverTime';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined,
  CloseCircleOutlined,
  CloudOutlined,
  GlobalOutlined,
  KeyOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getLicenseList, getLicenseStatus } from '../../api/license';
import logger from '../../utils/logger';
import type { LicenseEdition } from '../../types/license';
import type { LicenseListItem, LicenseStatus } from '../../types/license';
import {
  EDITION_CONFIG,
  FEATURE_CATALOG,
  STATUS_CONFIG,
  type LicenseFeatureDef,
  type LicenseStatusCode,
} from './constants/featureCatalog';
import { ActivateLicenseModal } from './components/ActivateLicenseModal';
import { FeatureDetailDrawer } from './components/FeatureDetailDrawer';
import { FeatureMatrix } from './components/FeatureMatrix';
import './license.css';
import { ManagementMetric, ManagementPageHeader } from '../layout/components/ManagementPage';
import './overview.css';

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
    setFeatures(
      FEATURE_CATALOG.map((f) => ({
        ...f,
        enabled: enabledFeatures.includes(f.name),
      })),
    );
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
    ? (EDITION_CONFIG[licenseInfo.edition as LicenseEdition] ?? EDITION_CONFIG.BASIC)
    : EDITION_CONFIG.BASIC;
  const quotaValue = (value: number | undefined) =>
    value === -1 ? t('licenseUI.unlimited') : value == null ? '—' : value.toLocaleString();

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
        return <Badge status={config.color as 'success' | 'error'} text={status} />;
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
    <div className="license-page management-page">
      <Spin spinning={loading}>
        {/* Page Header */}
        <ManagementPageHeader
          title={t('licenseUI.title')}
          subtitle={t('licenseUI.subtitle')}
          eyebrow={t('licenseUI.workspace_label')}
          actions={
            <>
              <Button
                icon={<ReloadOutlined spin={loading} />}
                onClick={fetchLicenseData}
                disabled={loading}
              >
                {t('common.refresh')}
              </Button>
              <Button
                type="primary"
                icon={<KeyOutlined />}
                onClick={() => setActivateModalVisible(true)}
              >
                {t('licenseUI.activate')}
              </Button>
            </>
          }
        />

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
                              <div className="edition-icon">{editionConfig.icon}</div>
                              <Title level={3}>{editionConfig.label}</Title>
                              <Tag color={licenseInfo.valid ? 'success' : 'error'}>
                                {licenseInfo.valid ? '有效' : '无效'}
                              </Tag>
                            </>
                          ) : (
                            <>
                              <div className="edition-icon">
                                <KeyOutlined />
                              </div>
                              <Title level={3}>{t('licenseUI.not_activated')}</Title>
                              <Tag>{t('licenseUI.basic_access')}</Tag>
                            </>
                          )}
                        </div>
                        <div className="license-summary-note">
                          <span>
                            {t('licenseUI.enabled_features', {
                              count:
                                licenseInfo?.features?.length ??
                                features.filter((feature) => feature.enabled).length,
                            })}
                          </span>
                          <span>
                            {licenseInfo?.expired
                              ? t('licenseUI.expired')
                              : licenseInfo?.daysRemaining != null &&
                                  licenseInfo.daysRemaining <= 365
                                ? t('licenseUI.days_remaining', {
                                    count: licenseInfo.daysRemaining,
                                  })
                                : licenseInfo?.expiresAt
                                  ? t('licenseUI.valid_until', {
                                      date: parseServerTime(
                                        licenseInfo.expiresAt,
                                      ).toLocaleDateString(),
                                    })
                                  : licenseInfo && !licenseInfo.expiresAt
                                    ? t('licenseUI.permanent')
                                    : t('licenseUI.activation_hint')}
                          </span>
                        </div>
                      </Card>
                    </Col>

                    <Col xs={24} lg={16}>
                      {/* Quota Overview */}
                      <Card title={t('licenseUI.quota_title')} className="quota-card">
                        <p className="license-quota-caption">{t('licenseUI.quota_hint')}</p>
                        <div className="license-quota-grid">
                          <ManagementMetric
                            title={t('licenseUI.instances')}
                            value={quotaValue(licenseInfo?.quotas?.maxInstances)}
                            icon={<CloudOutlined />}
                          />
                          <ManagementMetric
                            title={t('licenseUI.routes')}
                            value={quotaValue(licenseInfo?.quotas?.maxRoutes)}
                            icon={<ApiOutlined />}
                          />
                          <ManagementMetric
                            title={t('licenseUI.services')}
                            value={quotaValue(licenseInfo?.quotas?.maxServices)}
                            icon={<GlobalOutlined />}
                          />
                          <ManagementMetric
                            title={t('licenseUI.qps')}
                            value={quotaValue(licenseInfo?.quotas?.maxRateLimitQPS)}
                            icon={<ThunderboltOutlined />}
                          />
                        </div>
                        <Divider className="quota-divider" />
                        <Descriptions
                          column={{ xs: 1, sm: 2, xl: 4 }}
                          layout="vertical"
                          colon={false}
                          size="small"
                          className="quota-descriptions"
                        >
                          {licenseInfo && (
                            <>
                              <Descriptions.Item label="License ID">
                                <Text
                                  copyable={{ text: licenseInfo.licenseId || '' }}
                                  style={{ fontSize: 12 }}
                                >
                                  {licenseInfo.licenseId || '—'}
                                </Text>
                              </Descriptions.Item>
                              <Descriptions.Item label="客户">
                                {licenseInfo.customerName || '-'}
                              </Descriptions.Item>
                              <Descriptions.Item label="组织">
                                {licenseInfo.organization || '-'}
                              </Descriptions.Item>
                              <Descriptions.Item label="有效期至">
                                {licenseInfo.expiresAt
                                  ? parseServerTime(licenseInfo.expiresAt).toLocaleDateString()
                                  : '永久'}
                              </Descriptions.Item>
                            </>
                          )}
                        </Descriptions>
                      </Card>
                    </Col>
                  </Row>

                  {/* Feature Matrix */}
                  <FeatureMatrix features={features} onFeatureClick={setSelectedFeature} />
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
                        emptyText: <Empty description="暂无 License 记录" />,
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

      <FeatureDetailDrawer feature={selectedFeature} onClose={() => setSelectedFeature(null)} />
    </div>
  );
};

export default LicensePage;
