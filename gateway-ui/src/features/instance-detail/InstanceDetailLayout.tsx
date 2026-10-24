/**
 * Instance detail console layout (rewritten).
 * Header with lifecycle actions, URL-driven tabs, Outlet for tab content.
 * Tab state lives in the URL (/instances/:instanceId/:tab) so refresh keeps it.
 */
import React, { useMemo, useState } from 'react';
import {
  Button, Result, Spin, Tabs, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  AlertOutlined, ApartmentOutlined, AppstoreOutlined, ArrowLeftOutlined,
  BellOutlined, BugOutlined, CloudServerOutlined, DashboardOutlined,
  DatabaseOutlined, DeploymentUnitOutlined, FileTextOutlined, FilterOutlined,
  HistoryOutlined, HomeOutlined, KeyOutlined, LineChartOutlined, LinkOutlined,
  LockOutlined, MedicineBoxOutlined, PauseCircleOutlined, PlayCircleOutlined,
  PlusSquareOutlined, ReloadOutlined, RobotOutlined, RocketOutlined,
  SafetyOutlined, StopOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import {
  getInstanceByInstanceId, getInstanceConfig, startInstance, stopInstance,
} from '../../api/instances';
import { ApiError } from '../../api/http';
import { getStatusColorByName, getStatusTextByName } from '../../utils/instance';
import LanguageSwitcher from '../layout/components/LanguageSwitcher';
import UserMenu from '../layout/UserMenu';
import { InstanceDetailProvider } from './InstanceDetailContext';
import ScaleModal from './ScaleModal';
import './instance-detail.css';

const { Title } = Typography;

/** Outlet indirection kept tiny so Suspense wraps only tab content. */
const OutletGuard: React.FC = () => <Outlet />;

/** Tab key → icon mapping (order defines tab order). */
const TAB_ICONS = {
  overview: CloudServerOutlined,
  services: AppstoreOutlined,
  routes: DeploymentUnitOutlined,
  plugins: RocketOutlined,
  plugin_bindings: LinkOutlined,
  strategies: SafetyOutlined,
  authentication: LockOutlined,
  token_revocation: StopOutlined,
  certificates: KeyOutlined,
  trace: LineChartOutlined,
  monitor: DashboardOutlined,
  alerts: BellOutlined,
  access_log: FileTextOutlined,
  audit_logs: HistoryOutlined,
  diagnostic: MedicineBoxOutlined,
  topology: ApartmentOutlined,
  filter_chain: FilterOutlined,
  request_replay: BugOutlined,
  copilot: RobotOutlined,
  stress_test: ThunderboltOutlined,
  events: AlertOutlined,
  resources: DashboardOutlined,
  middleware: DatabaseOutlined,
} as const;

export type TabKey = keyof typeof TAB_ICONS;

const TAB_ORDER = Object.keys(TAB_ICONS) as TabKey[];

const tabI18nKey = (key: TabKey): string => {
  if (key === 'overview') return 'instance.overview';
  return `menu.${key}`;
};

const InstanceDetailLayout: React.FC = () => {
  const { instanceId } = useParams<{ instanceId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();
  const [scaleModalOpen, setScaleModalOpen] = useState(false);

  const instanceRequest = useRequest(
    (signal) => getInstanceByInstanceId(instanceId ?? '', { signal }),
    { deps: [instanceId] },
  );
  const configRequest = useRequest((signal) => getInstanceConfig({ signal }));

  const instance = instanceRequest.data;
  const globalNacosAddr = configRequest.data?.nacosServerAddr || '127.0.0.1:8848';

  const activeTab = useMemo<TabKey>(() => {
    const parts = location.pathname.split('/');
    const candidate = parts[3];
    return candidate && TAB_ORDER.includes(candidate as TabKey)
      ? (candidate as TabKey)
      : 'overview';
  }, [location.pathname]);

  const handleTabChange = (key: string) => {
    navigate(key === 'overview'
      ? `/instances/${instanceId}`
      : `/instances/${instanceId}/${key}`);
  };

  const runLifecycle = async (
    action: () => Promise<unknown>,
    successKey: string,
    failKey: string,
  ) => {
    try {
      await action();
      message.success(t(successKey));
      instanceRequest.run();
    } catch (error) {
      const msg = error instanceof ApiError && error.message ? error.message : t(failKey);
      message.error(msg);
    }
  };

  const tabItems = TAB_ORDER.map((key) => {
    const Icon = TAB_ICONS[key];
    return {
      key,
      label: (
        <span>
          <Icon />
          {t(tabI18nKey(key))}
        </span>
      ),
    };
  });

  if (instanceRequest.loading && !instance) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!instance) {
    return (
      <div style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: 400,
        padding: 24,
      }}
      >
        <Result
          status="warning"
          title={t('instance.not_found')}
          subTitle="该实例可能已被删除或您没有访问权限"
          extra={[
            <Button
              key="reload"
              type="primary"
              icon={<ReloadOutlined />}
              onClick={() => instanceRequest.run()}
            >
              刷新页面
            </Button>,
            <Button
              key="home"
              icon={<HomeOutlined />}
              onClick={() => navigate('/instances')}
            >
              返回列表
            </Button>,
          ]}
        />
      </div>
    );
  }

  const statusText = getStatusTextByName(instance.status ?? '', t);
  const statusColor = getStatusColorByName(instance.status ?? '');

  return (
    <InstanceDetailProvider value={{ instance, globalNacosAddr, refresh: instanceRequest.run }}>
      <div className="instance-detail-page">
        {/* Header */}
        <div className="instance-detail-header">
          <div className="instance-detail-header-left">
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/instances')}
              className="instance-detail-back-btn"
            />
            <CloudServerOutlined className="instance-detail-icon" />
            <Title level={4} className="instance-detail-title">
              {instance.instanceName}
            </Title>
            <Tag className="instance-detail-status-tag" color={statusColor}>
              {statusText}
            </Tag>
          </div>

          <div className="instance-detail-header-right">
            {instance.status === 'Running' && (
              <>
                <Button icon={<PlusSquareOutlined />} onClick={() => setScaleModalOpen(true)}>
                  {t('instance.scale')}
                </Button>
                <Button
                  icon={<PauseCircleOutlined />}
                  onClick={() => runLifecycle(
                    () => stopInstance(instance.id),
                    'instance.stop_success',
                    'instance.stop_failed',
                  )}
                >
                  {t('instance.stop')}
                </Button>
              </>
            )}
            {instance.status === 'Stopped' && (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => runLifecycle(
                  () => startInstance(instance.id),
                  'instance.start_success',
                  'instance.start_failed',
                )}
              >
                {t('instance.start')}
              </Button>
            )}
            <Tooltip title={t('common.refresh')}>
              <Button icon={<ReloadOutlined />} onClick={() => instanceRequest.run()} />
            </Tooltip>
            <LanguageSwitcher />
            <span style={{ marginLeft: 12 }}>
              <UserMenu />
            </span>
          </div>
        </div>

        {/* Tabs (URL-driven) */}
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={tabItems}
          className="instance-detail-tabs"
        />

        {/* Tab content via router Outlet */}
        <div className="instance-detail-content">
          <React.Suspense fallback={(
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 300 }}>
              <Spin size="large" />
            </div>
          )}
          >
            <OutletGuard />
          </React.Suspense>
        </div>

        {scaleModalOpen && (
          <ScaleModal
            open={scaleModalOpen}
            instance={instance}
            onClose={() => setScaleModalOpen(false)}
            onScaled={() => instanceRequest.run()}
          />
        )}
      </div>
    </InstanceDetailProvider>
  );
};

export default InstanceDetailLayout;
