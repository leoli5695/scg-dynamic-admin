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
  SafetyOutlined, StopOutlined, ThunderboltOutlined, SearchOutlined, FundOutlined, DownOutlined,
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
import { useSystemSettings } from '../settings/SystemSettingsContext';
import './instance-detail.css';
import './console-ui.css';
import './operations-ui.css';
import './instance-navigation.css';

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
  requests: SearchOutlined,
  token_usage: FundOutlined,
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
  const { settings } = useSystemSettings();
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
    if (key === activeTab) return;
    navigate(key === 'overview'
      ? `/instances/${encodeURIComponent(instanceId ?? '')}`
      : `/instances/${encodeURIComponent(instanceId ?? '')}/${key}`);
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

  const navigation = getInstanceNavigation(isExternal, {
    stressTest: settings?.stressTestEnabled !== false,
    middleware: settings?.middlewareEnabled !== false,
    plugins: settings?.pluginsEnabled !== false,
  });
  const activeGroup = navigation.find((group) => group.pages.some((page) => page === activeTab));
  const groupIcons = {
    overview: CloudServerOutlined, services: AppstoreOutlined, routes: DeploymentUnitOutlined,
    requests: SearchOutlined, diagnostics: MedicineBoxOutlined, usage: FundOutlined,
    security: SafetyOutlined, operations: HistoryOutlined, tools: ThunderboltOutlined,
  };
  const tools = navigation.find(group => group.key === 'tools')!;

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

          <div className="instance-detail-header-right console-header-actions">
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
              <Button className="console-refresh" aria-label={t('common.refresh')} loading={instanceRequest.loading} icon={<ReloadOutlined />} onClick={() => instanceRequest.run()} />
            </Tooltip>
            <LanguageSwitcher />
            <UserMenu />
          </div>
        </div>

        <nav className="instance-navigation" aria-label={t('instance_nav.label')}>
          <div className="instance-navigation-groups">
            {navigation.filter(group => group.key !== 'tools').map((group) => {
              const Icon = groupIcons[group.key];
              const selected = activeGroup?.key === group.key;
              const label = group.key === 'overview'
                ? t('instance.overview') : t(`instance_nav.${group.key}`);
              return (
                <button
                  key={group.key}
                  type="button"
                  className={`instance-navigation-group${selected ? ' is-active' : ''}`}
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => handleTabChange(selected ? activeTab : group.pages[0])}
                >
                  <Icon /><span>{label}</span>
                </button>
              );
            })}
          </div>
          <Dropdown trigger={['click']} menu={{
            selectedKeys: [activeTab],
            onClick: ({ key }) => handleTabChange(key),
            items: tools.pages.map(page => ({ key: page, label: t(tabI18nKey(page)) })),
          }}>
            <Button type="text" className={activeGroup?.key === 'tools' ? 'is-active' : undefined}>
              {t('instance_nav.tools')} <DownOutlined />
            </Button>
          </Dropdown>
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
          {activeGroup && activeGroup.pages.length > 1 && (
            <div className="instance-navigation-pages" aria-label={t(`instance_nav.${activeGroup.key}`)}>
              {activeGroup.pages.map((page) => {
                const PageIcon = TAB_ICONS[page];
                return (
                  <button
                    key={page}
                    type="button"
                    className={`instance-navigation-page${page === activeTab ? ' is-active' : ''}`}
                    aria-current={page === activeTab ? 'page' : undefined}
                    onClick={() => handleTabChange(page)}
                  >
                    <PageIcon /><span>{t(tabI18nKey(page))}</span>
                  </button>
                );
              })}
            </div>
          )}
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
