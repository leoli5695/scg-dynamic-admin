/**
 * Instance console with grouped navigation and URL-driven page selection.
 */
import React, { useMemo, useState } from 'react';
import {
  Button, Dropdown, Result, Select, Spin, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  AlertOutlined, ApartmentOutlined, AppstoreOutlined, ArrowLeftOutlined,
  BellOutlined, BugOutlined, CloudServerOutlined, DashboardOutlined,
  DatabaseOutlined, DeploymentUnitOutlined, FileTextOutlined, FilterOutlined,
  HistoryOutlined, HomeOutlined, KeyOutlined, LineChartOutlined, LinkOutlined,
  LockOutlined, MedicineBoxOutlined, PauseCircleOutlined, PlayCircleOutlined,
  PlusSquareOutlined, ReloadOutlined, RobotOutlined, RocketOutlined,
  SafetyOutlined, StopOutlined, ThunderboltOutlined, DownOutlined, SearchOutlined,
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
import { getInstanceNavigation } from './instanceNavigation';
import './instance-navigation.css';
import './instance-detail.css';
import './console-ui.css';
import './operations-ui.css';

const { Title } = Typography;

/** Outlet indirection kept tiny so Suspense wraps only tab content. */
const OutletGuard: React.FC = () => <Outlet />;

/** Route key → icon mapping. */
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
  /**
   * No clusterId means the gateway is externally managed: the console owns its configuration but
   * not its process, so the Kubernetes-only tabs and lifecycle buttons are hidden rather than
   * left to fail with a 400.
   */
  const isExternal = instance.clusterId == null;

  const navigation = getInstanceNavigation(isExternal);
  const activeGroup = navigation.find((group) => group.pages.some((page) => page === activeTab));
  const groupIcons = {
    overview: CloudServerOutlined, traffic: DeploymentUnitOutlined,
    security: SafetyOutlined, observability: DashboardOutlined,
    diagnostics: MedicineBoxOutlined, tools: ThunderboltOutlined,
  };

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
            {isExternal && (
              <Tooltip title={t('instance.external_lifecycle_hint')}>
                <Tag color="purple">{t('instance.external_tag')}</Tag>
              </Tooltip>
            )}
          </div>

          <div className="instance-detail-header-right">
            {!isExternal && instance.status === 'Running' && (
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
            {!isExternal && instance.status === 'Stopped' && (
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

        <nav className="instance-navigation" aria-label={t('instance_nav.label')}>
          <div className="instance-navigation-groups">
            {navigation.map((group) => {
              const Icon = groupIcons[group.key];
              const selected = activeGroup?.key === group.key;
              const label = group.key === 'overview'
                ? t('instance.overview') : t(`instance_nav.${group.key}`);
              const button = (
                <button
                  type="button"
                  className={`instance-navigation-group${selected ? ' is-active' : ''}`}
                  aria-current={selected ? 'true' : undefined}
                  onClick={group.key === 'overview' ? () => handleTabChange('overview') : undefined}
                >
                  <Icon /><span>{label}</span>
                  {group.pages.length > 1 && <DownOutlined className="instance-navigation-chevron" />}
                </button>
              );
              return group.key === 'overview' ? <React.Fragment key={group.key}>{button}</React.Fragment> : (
                <Dropdown
                  key={group.key}
                  trigger={['click']}
                  overlayClassName="instance-navigation-dropdown"
                  menu={{
                    selectedKeys: [activeTab],
                    onClick: ({ key }) => handleTabChange(key),
                    items: group.pages.map((page) => {
                      const PageIcon = TAB_ICONS[page];
                      return { key: page, icon: <PageIcon />, label: t(tabI18nKey(page)) };
                    }),
                  }}
                >{button}</Dropdown>
              );
            })}
          </div>
          <Select<string, { value: string; label: string; searchText: string }>
            className="instance-navigation-search"
            showSearch={{
              filterOption: (input, option) => String(option?.searchText ?? '').toLocaleLowerCase().includes(input.trim().toLocaleLowerCase()),
            }}
            value={null}
            placeholder={<><SearchOutlined /> {t('instance_nav.search')}</>}
            aria-label={t('instance_nav.search')}
            onChange={handleTabChange}
            popupMatchSelectWidth={280}
            options={navigation.flatMap((group) => group.pages.map((page) => ({
              value: page,
              label: t(tabI18nKey(page)),
              searchText: `${t(tabI18nKey(page))} ${page} ${group.key === 'overview' ? '' : t(`instance_nav.${group.key}`)}`,
            })))}
          />
          <div className="instance-navigation-location" aria-live="polite">
            {activeGroup && activeGroup.key !== 'overview' && <><span>{t(`instance_nav.${activeGroup.key}`)}</span><span>/</span></>}
            <strong>{t(tabI18nKey(activeTab))}</strong>
          </div>
        </nav>

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
