import { useState, useEffect } from 'react';
import {
  Card, Button, Space, message, Spin, Tag, Modal, Form, Input, Switch,
  Select, Empty, Radio, Tooltip, Badge, Divider, Typography, Dropdown, Pagination,
  InputNumber, Slider, Collapse
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EyeOutlined, CopyOutlined, StopOutlined,
  PlayCircleOutlined, EditOutlined, CompassOutlined, MoreOutlined,
  ApiOutlined, BranchesOutlined, ThunderboltOutlined, FileTextOutlined,
  CloudOutlined, GlobalOutlined, FilterOutlined, SplitCellsOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import copy from 'copy-to-clipboard';

const { Text, Title } = Typography;
const { TextArea } = Input;

// Routing mode enum - matches backend
type RoutingMode = 'SINGLE' | 'MULTI';

// Route service binding - matches backend RouteServiceBinding
interface RouteServiceBinding {
  serviceId: string;
  serviceName: string;
  weight: number;
  version?: string;
  enabled: boolean;
  description?: string;
}

// Gray rule - matches backend GrayRule
interface GrayRule {
  type: 'HEADER' | 'COOKIE' | 'QUERY' | 'WEIGHT';
  name?: string;
  value: string;
  targetVersion: string;
  description?: string;
}

// Gray rules - matches backend GrayRules
interface GrayRules {
  enabled: boolean;
  rules: GrayRule[];
}

interface Route {
  id: string;
  routeName: string;
  uri: string;
  mode?: RoutingMode;
  serviceId?: string;
  services?: RouteServiceBinding[];
  grayRules?: GrayRules;
  predicates?: any[];
  filters?: any[];
  order?: number;
  enabled?: boolean;
  description?: string;
  metadata?: any;
}

interface Service {
  name: string;
  serviceId: string;
  loadBalancer: string;
  instances?: any[];
}

interface NacosService {
  serviceName: string;
}

// Predicate Item Component
const PredicateItem: React.FC<{
  form: any;
  restField: any;
  name: number;
  t: any;
  onRemove: (index: number) => void;
}> = ({ form, restField, name, t, onRemove }) => {
  const [predicateType, setPredicateType] = useState<string>('');

  return (
    <div key={name} className="form-item-row">
      <Form.Item {...restField} name={[name, 'name']} noStyle>
        <Select
          placeholder={t('routes.predicate_type')}
          style={{ width: 150 }}
          onChange={(value) => setPredicateType(value)}
        >
          <Select.Option value="Path">Path</Select.Option>
          <Select.Option value="Host">Host</Select.Option>
          <Select.Option value="Method">Method</Select.Option>
          <Select.Option value="Header">Header</Select.Option>
        </Select>
      </Form.Item>
      <Form.Item {...restField} name={[name, 'args']} noStyle>
        {predicateType === 'Method' ? (
          <Select placeholder={t('routes.arguments')} style={{ width: 300 }} mode="multiple">
            <Select.Option value="GET">GET</Select.Option>
            <Select.Option value="POST">POST</Select.Option>
            <Select.Option value="PUT">PUT</Select.Option>
            <Select.Option value="DELETE">DELETE</Select.Option>
            <Select.Option value="PATCH">PATCH</Select.Option>
            <Select.Option value="HEAD">HEAD</Select.Option>
            <Select.Option value="OPTIONS">OPTIONS</Select.Option>
          </Select>
        ) : predicateType === 'Header' ? (
          <Space.Compact style={{ width: 300 }}>
            <Input
              placeholder="Header Name"
              style={{ width: '45%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['predicates', name, 'args']);
                const newValue = `${e.target.value}:${currentValue?.split(':')[1] || ''}`;
                form.setFieldValue(['predicates', name, 'args'], newValue);
              }}
            />
            <Input
              placeholder="Header Value"
              style={{ width: '55%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['predicates', name, 'args']);
                const newValue = `${currentValue?.split(':')[0] || ''}:${e.target.value}`;
                form.setFieldValue(['predicates', name, 'args'], newValue);
              }}
            />
          </Space.Compact>
        ) : (
          <Input placeholder={t('routes.arguments')} style={{ width: 300 }} />
        )}
      </Form.Item>
      <Button type="text" danger onClick={() => onRemove(name)} icon={<DeleteOutlined />} />
    </div>
  );
};

// Filter Item Component
const FilterItem: React.FC<{
  form: any;
  restField: any;
  name: number;
  t: any;
  onRemove: (index: number) => void;
}> = ({ form, restField, name, t, onRemove }) => {
  const [filterType, setFilterType] = useState<string>('');

  const needsKeyValueInput = [
    'AddRequestHeader', 'SetRequestHeader', 'AddRequestParameter',
    'AddResponseHeader', 'SetResponseHeader'
  ];

  const isRewritePath = filterType === 'RewritePath';

  return (
    <div key={name} className="form-item-row">
      <Form.Item {...restField} name={[name, 'name']} noStyle>
        <Select
          placeholder={t('routes.filter_type')}
          style={{ width: 200 }}
          onChange={(value) => setFilterType(value)}
        >
          <Select.OptGroup label={t('plugin.category.request_headers')}>
            <Select.Option value="AddRequestHeader">{t('plugin.AddRequestHeader.name')}</Select.Option>
            <Select.Option value="RemoveRequestHeader">{t('plugin.RemoveRequestHeader.name')}</Select.Option>
            <Select.Option value="SetRequestHeader">{t('plugin.SetRequestHeader.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.request_params')}>
            <Select.Option value="AddRequestParameter">{t('plugin.AddRequestParameter.name')}</Select.Option>
            <Select.Option value="RemoveRequestParameter">{t('plugin.RemoveRequestParameter.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.response_headers')}>
            <Select.Option value="AddResponseHeader">{t('plugin.AddResponseHeader.name')}</Select.Option>
            <Select.Option value="RemoveResponseHeader">{t('plugin.RemoveResponseHeader.name')}</Select.Option>
            <Select.Option value="SetResponseHeader">{t('plugin.SetResponseHeader.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.path_modification')}>
            <Select.Option value="StripPrefix">{t('plugin.StripPrefix.name')}</Select.Option>
            <Select.Option value="PrefixPath">{t('plugin.PrefixPath.name')}</Select.Option>
            <Select.Option value="RewritePath">{t('plugin.RewritePath.name')}</Select.Option>
            <Select.Option value="SetPath">{t('plugin.SetPath.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.status_code')}>
            <Select.Option value="SetStatus">{t('plugin.SetStatus.name')}</Select.Option>
            <Select.Option value="RedirectTo">{t('plugin.RedirectTo.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.security')}>
            <Select.Option value="SecureHeaders">{t('plugin.SecureHeaders.name')}</Select.Option>
          </Select.OptGroup>
          <Select.OptGroup label={t('plugin.category.request_size')}>
            <Select.Option value="RequestSize">{t('plugin.RequestSize.name')}</Select.Option>
          </Select.OptGroup>
        </Select>
      </Form.Item>
      <Form.Item {...restField} name={[name, 'args']} noStyle>
        {needsKeyValueInput.includes(filterType) ? (
          <Space.Compact style={{ width: 320 }}>
            <Input
              placeholder="Key"
              style={{ width: '45%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${e.target.value}:${currentValue?.split(':')[1] || ''}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
            <Input
              placeholder="Value"
              style={{ width: '55%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${currentValue?.split(':')[0] || ''}:${e.target.value}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
          </Space.Compact>
        ) : isRewritePath ? (
          <Space.Compact style={{ width: 320 }}>
            <Input
              placeholder="Regexp"
              style={{ width: '50%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${e.target.value}|${currentValue?.split('|')[1] || ''}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
            <Input
              placeholder="Replacement"
              style={{ width: '50%' }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${currentValue?.split('|')[0] || ''}|${e.target.value}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
          </Space.Compact>
        ) : (
          <Input placeholder={t('routes.arguments')} style={{ width: 320 }} disabled={!filterType} />
        )}
      </Form.Item>
      {filterType && (
        <div className="plugin-desc">
          <Tooltip title={t(`plugin.${filterType}.detail`)}>
            <span style={{ cursor: 'help' }}>ℹ️ {t(`plugin.${filterType}.desc`)}...</span>
          </Tooltip>
        </div>
      )}
      <Button type="text" danger onClick={() => onRemove(name)} icon={<DeleteOutlined />} />
    </div>
  );
};

const RoutesPage: React.FC = () => {
  const [routes, setRoutes] = useState<Route[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [nacosServices, setNacosServices] = useState<NacosService[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [selectedRoute, setSelectedRoute] = useState<Route | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  // Routing mode: SINGLE (single service) or MULTI (multi-service for gray release)
  const [routingMode, setRoutingMode] = useState<RoutingMode>('SINGLE');
  const [editRoutingMode, setEditRoutingMode] = useState<RoutingMode>('SINGLE');
  // Gray rules enabled state
  const [grayRulesEnabled, setGrayRulesEnabled] = useState(false);
  const [editGrayRulesEnabled, setEditGrayRulesEnabled] = useState(false);
  const { t } = useTranslation();

  useEffect(() => {
    loadRoutes();
    loadServices();
    loadNacosServices();
  }, []);

  // Reset to first page when search/filter changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchTerm, statusFilter]);

  const loadRoutes = async () => {
    try {
      setLoading(true);
      const response = await api.get('/api/routes');
      if (response.data.code === 200) {
        setRoutes(response.data.data || []);
      } else {
        message.error(t('message.load_routes_failed', { error: response.data.message }));
      }
    } catch (error: any) {
      console.error('Load routes error:', error);
      message.error(t('message.load_routes_failed', { error: error.response?.data?.message || error.message }));
    } finally {
      setLoading(false);
    }
  };

  const loadServices = async () => {
    try {
      const response = await api.get('/api/services');
      if (response.data.code === 200) {
        setServices(response.data.data || []);
      }
    } catch (error: any) {
      console.error('Load services error:', error);
    }
  };

  const loadNacosServices = async () => {
    try {
      const response = await api.get('/api/services/nacos-discovery');
      if (response.data.code === 200) {
        const nacosServiceList: string[] = response.data.data || [];
        setNacosServices(nacosServiceList.map(name => ({ serviceName: name })));
      }
    } catch (error: any) {
      console.error('Load Nacos services error:', error);
    }
  };

  const filteredRoutes = routes.filter(route => {
    const matchesSearch = !searchTerm ||
      route.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (route.routeName && route.routeName.toLowerCase().includes(searchTerm.toLowerCase())) ||
      (route.uri && route.uri.toLowerCase().includes(searchTerm.toLowerCase()));

    const matchesStatus = statusFilter === 'all' ||
      (statusFilter === 'enabled' && route.enabled) ||
      (statusFilter === 'disabled' && !route.enabled);

    return matchesSearch && matchesStatus;
  });

  // Pagination
  const totalRoutes = routes.length;
  const enabledRoutes = routes.filter(r => r.enabled).length;
  const startIndex = (currentPage - 1) * pageSize;
  const endIndex = startIndex + pageSize;
  const paginatedRoutes = filteredRoutes.slice(startIndex, endIndex);

  const handleCreate = (values: any) => {
    if (!values.predicates || values.predicates.length === 0) {
      message.error(t('routes.predicate_required'));
      return;
    }

    const hasPathPredicate = values.predicates.some((p: any) => p.name === 'Path');
    if (!hasPathPredicate) {
      message.error(t('routes.path_predicate_required'));
      return;
    }

    const formattedPredicates = values.predicates.map((p: any) => ({
      name: p.name,
      args: parsePredicateArgs(p.name, p.args)
    }));

    const formattedFilters = (values.filters || []).map((f: any) => ({
      name: f.name,
      args: parseFilterArgs(f.name, f.args)
    }));

    // Build route data based on routing mode
    const routeData: any = {
      id: values.id,
      order: values.order || 0,
      enabled: values.enabled !== false,
      description: values.description,
      predicates: formattedPredicates,
      filters: formattedFilters,
      mode: routingMode
    };

    if (routingMode === 'SINGLE') {
      // Single service mode - serviceId is already full URI (static://xxx or lb://xxx)
      routeData.uri = values.serviceId || values.uri;
      // Extract serviceId from URI for backend compatibility
      if (values.serviceId) {
        const uriParts = values.serviceId.split('://');
        if (uriParts.length === 2) {
          routeData.serviceId = uriParts[1];
        }
      }
    } else {
      // Multi-service mode
      const serviceBindings = values.services || [];
      if (serviceBindings.length === 0) {
        message.error(t('routes.at_least_one_service'));
        return;
      }

      routeData.services = serviceBindings.map((s: any) => {
        // s.serviceId is now full URI (static://xxx or lb://xxx)
        const uriParts = (s.serviceId || '').split('://');
        const protocol = uriParts[0] || 'static';
        const serviceName = uriParts[1] || s.serviceId;
        return {
          serviceId: serviceName,
          serviceName: services.find(svc => svc.serviceId === serviceName)?.name || serviceName,
          weight: s.weight || 100,
          version: s.version,
          enabled: s.enabled !== false,
          protocol: protocol
        };
      });

      // Add gray rules if enabled
      if (grayRulesEnabled && values.grayRules && values.grayRules.length > 0) {
        routeData.grayRules = {
          enabled: true,
          rules: values.grayRules.map((r: any) => ({
            type: r.type,
            name: r.name,
            value: String(r.value),
            targetVersion: r.targetVersion
          }))
        };
      }

      // Generate URI from first service for backward compatibility
      routeData.uri = serviceBindings[0]?.serviceId || 'static://multi-service';
    }

    api.post('/api/routes', routeData)
      .then(response => {
        if (response.data.code === 200) {
          message.success(t('routes.created_success'));
          createForm.resetFields();
          setCreateModalVisible(false);
          setRoutingMode('SINGLE');
          setGrayRulesEnabled(false);
          loadRoutes();
          loadServices();
        } else {
          message.error(t('routes.create_failed') + ': ' + response.data.message);
        }
      })
      .catch(error => {
        message.error(t('routes.create_failed') + ': ' + (error.response?.data?.message || error.message));
      });
  };

  const handleUpdate = (values: any) => {
    if (!values.predicates || values.predicates.length === 0) {
      message.error(t('routes.predicate_required'));
      return;
    }

    const hasPathPredicate = values.predicates.some((p: any) => p.name === 'Path');
    if (!hasPathPredicate) {
      message.error(t('routes.path_predicate_required'));
      return;
    }

    const formattedPredicates = values.predicates.map((p: any) => ({
      name: p.name,
      args: parsePredicateArgs(p.name, p.args)
    }));

    const formattedFilters = (values.filters || []).map((f: any) => ({
      name: f.name,
      args: parseFilterArgs(f.name, f.args)
    }));

    const routeData = {
      id: values.id,
      uri: values.uri,
      order: values.order || 0,
      enabled: values.enabled !== false,
      description: values.description,
      predicates: formattedPredicates,
      filters: formattedFilters
    };

    api.put(`/api/routes/${values.id}`, routeData)
      .then(response => {
        if (response.data.code === 200) {
          message.success(t('message.update_success'));
          editForm.resetFields();
          setEditModalVisible(false);
          loadRoutes();
          loadServices();
        } else {
          message.error(t('routes.update_failed') + ': ' + response.data.message);
        }
      })
      .catch(error => {
        message.error(t('routes.update_failed') + ': ' + (error.response?.data?.message || error.message));
      });
  };

  const parsePredicateArgs = (type: string, args: string) => {
    switch (type) {
      case 'Path':
        return { pattern: args || '/**' };
      case 'Host':
        return { pattern: args };
      case 'Method':
        return { methods: args ? args.split(',') : ['GET'] };
      case 'Header':
        const parts = args.split(':');
        return { name: parts[0], regexp: parts[1] || '.*' };
      default:
        return { value: args };
    }
  };

  const parseFilterArgs = (type: string, args: string) => {
    switch (type) {
      case 'StripPrefix':
        return { parts: parseInt(args) || 1 };
      case 'AddRequestHeader':
      case 'SetRequestHeader':
        const headerParts = args.split(':');
        return { name: headerParts[0] || '', value: headerParts[1] || '' };
      case 'AddRequestParameter':
        const paramParts = args.split(':');
        return { name: paramParts[0] || '', value: paramParts[1] || '' };
      case 'SetPath':
        return { template: args };
      default:
        return { value: args };
    }
  };

  const stringifyPredicateArgs = (type: string, args: any): string => {
    if (!args) return '';
    switch (type) {
      case 'Path':
        return args.pattern || '/**';
      case 'Host':
        return args.pattern || '';
      case 'Method':
        return Array.isArray(args.methods) ? args.methods.join(',') : '';
      case 'Header':
        return `${args.name || ''}:${args.regexp || ''}`;
      default:
        return args.value || '';
    }
  };

  const stringifyFilterArgs = (type: string, args: any): string => {
    if (!args) return '';
    switch (type) {
      case 'StripPrefix':
        return String(args.parts || 1);
      case 'AddRequestHeader':
      case 'SetRequestHeader':
        return `${args.name || ''}:${args.value || ''}`;
      case 'AddRequestParameter':
        return `${args.name || ''}:${args.value || ''}`;
      case 'SetPath':
        return args.template || '';
      default:
        return args.value || '';
    }
  };

  const showRouteDetail = (record: Route) => {
    setSelectedRoute(record);
    setDetailModalVisible(true);
  };

  const showRouteEdit = (record: Route) => {
    let serviceId = '';
    if (record.uri) {
      if (record.uri.startsWith('static://')) {
        serviceId = record.uri.substring(9);
      }
    }

    const editPredicates = (record.predicates || []).map((p: any) => ({
      name: p.name,
      args: stringifyPredicateArgs(p.name, p.args)
    }));

    const editFilters = (record.filters || []).map((f: any) => ({
      name: f.name,
      args: stringifyFilterArgs(f.name, f.args)
    }));

    editForm.setFieldsValue({
      id: record.id,
      routeName: record.routeName,
      uri: record.uri,
      order: record.order,
      description: record.description,
      routingMode: record.mode || 'SINGLE',
      serviceId: serviceId || undefined,
      services: record.services || [],
      predicates: editPredicates,
      filters: editFilters,
    });
    setEditRoutingMode(record.mode || 'SINGLE');
    setEditGrayRulesEnabled(record.grayRules?.enabled || false);

    setEditModalVisible(true);
  };

  const copyToClipboard = (text: string, label: string) => {
    copy(text);
    message.success(t('message.copied_to_clipboard', { label }));
  };

  const handleDelete = (record: Route) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_route', { name: record.routeName, routeId: record.id || 'N/A' }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const routeIdToDelete = record.id;
          if (!routeIdToDelete) {
            message.error(t('message.route_uuid_not_found'));
            return;
          }
          const response = await api.delete(`/api/routes/${routeIdToDelete}`);
          if (response.data.code === 200) {
            message.success(t('message.delete_success'));
            loadRoutes();
          } else {
            message.error(t('routes.delete_failed') + ': ' + response.data.message);
          }
        } catch (error: any) {
          const errorMsg = error.response?.data?.message || error.message;
          message.error(t('routes.delete_failed') + ': ' + errorMsg);
        }
      },
    });
  };

  const handleEnableRoute = async (record: Route) => {
    try {
      const routeId = record.id;
      if (!routeId) {
        message.error(t('routes.id_not_found'));
        return;
      }
      const response = await api.post(`/api/routes/${routeId}/enable`);
      if (response.data.code === 200) {
        message.success(t('routes.enabled_success'));
        loadRoutes();
      } else {
        message.error(t('routes.enable_failed') + ': ' + response.data.message);
      }
    } catch (error: any) {
      const errorMsg = error.response?.data?.message || error.message;
      message.error(t('routes.enable_failed') + ': ' + errorMsg);
    }
  };

  const handleDisableRoute = async (record: Route) => {
    try {
      const routeId = record.id;
      if (!routeId) {
        message.error(t('routes.id_not_found'));
        return;
      }
      const response = await api.post(`/api/routes/${routeId}/disable`);
      if (response.data.code === 200) {
        message.success(t('routes.disabled_success'));
        loadRoutes();
      } else {
        message.error(t('routes.disable_failed') + ': ' + response.data.message);
      }
    } catch (error: any) {
      const errorMsg = error.response?.data?.message || error.message;
      message.error(t('routes.disable_failed') + ': ' + errorMsg);
    }
  };

  const getActionMenu = (route: Route): MenuProps['items'] => [
    { key: 'edit', icon: <EditOutlined />, label: t('common.edit'), onClick: () => showRouteEdit(route) },
    { key: 'detail', icon: <EyeOutlined />, label: t('common.detail'), onClick: () => showRouteDetail(route) },
    { type: 'divider' },
    route.enabled
      ? { key: 'disable', icon: <StopOutlined />, label: t('common.disable'), onClick: () => handleDisableRoute(route) }
      : { key: 'enable', icon: <PlayCircleOutlined />, label: t('common.enable'), onClick: () => handleEnableRoute(route) },
    { type: 'divider' },
    { key: 'delete', icon: <DeleteOutlined />, label: t('common.delete'), danger: true, onClick: () => handleDelete(route) },
  ];

  const getPredicateTag = (predicate: any) => {
    const name = predicate.name;
    let value = '';
    if (name === 'Path') value = predicate.args?.pattern || '/**';
    else if (name === 'Host') value = predicate.args?.pattern || '';
    else if (name === 'Method') value = Array.isArray(predicate.args?.methods) ? predicate.args.methods.join(', ') : '';
    else if (name === 'Header') value = predicate.args?.name || '';
    else value = JSON.stringify(predicate.args);

    const desc = t(`predicate.${name}.detail`, { defaultValue: '' });

    return (
      <Tooltip key={name} title={desc} placement="top">
        <Tag className="predicate-tag">
          <BranchesOutlined /> {name}: <code>{value}</code>
        </Tag>
      </Tooltip>
    );
  };

  return (
    <div className="routes-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{totalRoutes}</div>
          <div className="stat-label">{t('routes.stats_routes')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{enabledRoutes}</div>
          <div className="stat-label">{t('routes.stats_enabled')}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('routes.title')}</Title>
          <Text type="secondary">{t('routes.page_description_helper')}</Text>
        </div>
        <div className="page-header-right">
          <Input.Search
            placeholder={t('routes.search_placeholder')}
            value={searchTerm}
            onChange={e => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
          />
          <Select
            placeholder={t('routes.status_filter_placeholder')}
            value={statusFilter}
            onChange={setStatusFilter}
            style={{ width: 140 }}
          >
            <Select.Option value="all">{t('common.all')}</Select.Option>
            <Select.Option value="enabled">{t('common.enabled')}</Select.Option>
            <Select.Option value="disabled">{t('common.disabled')}</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)} className="create-btn">
            {t('routes.create')}
          </Button>
        </div>
      </div>

      {/* Routes Grid */}
      <Spin spinning={loading}>
        {filteredRoutes.length === 0 ? (
          <Card className="empty-card">
            <Empty
              image={<CompassOutlined className="empty-icon" />}
              description={<span className="empty-text">{t('routes.empty_description')}</span>}
            >
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
                {t('routes.create_first')}
              </Button>
            </Empty>
          </Card>
        ) : (
          <>
            <div className="routes-grid">
              {paginatedRoutes.map((route) => {
              const predicateCount = route.predicates?.length || 0;
              const pluginCount = route.filters?.length || 0;
              const isMultiService = route.mode === 'MULTI' && route.services && route.services.length > 0;
              return (
                <Card key={route.id} className="route-card" hoverable>
                  <div className="route-card-header">
                    <div className="route-info">
                      <div className="route-icon"><ApiOutlined /></div>
                      <div className="route-details">
                        <Text strong className="route-name">{route.routeName || (route.id && route.id.substring(0, 8)) || 'Unknown'}</Text>
                        <Text type="secondary" className="route-id">{route.id ? route.id.substring(0, 16) : 'N/A'}...</Text>
                      </div>
                    </div>
                    <div className="route-status">
                      <Badge status={route.enabled ? 'success' : 'default'} text={route.enabled ? t('common.enabled') : t('common.disabled')} />
                    </div>
                    <Dropdown menu={{ items: getActionMenu(route) }} trigger={['click']} placement="bottomRight">
                      <Button type="text" icon={<MoreOutlined />} className="action-btn" />
                    </Dropdown>
                  </div>

                  {/* Target Service(s) */}
                  <div className="route-section">
                    <Text type="secondary" className="section-label">
                      {isMultiService ? <SplitCellsOutlined /> : <CloudOutlined />} {isMultiService ? t('routes.services') : t('routes.target_uri')}
                    </Text>
                    <div className="tags-container">
                      {isMultiService ? (
                        route.services!.map((s, idx) => (
                          <Tag key={idx} className="service-tag" color={s.enabled ? 'blue' : 'default'}>
                            {s.serviceName || s.serviceId} ({s.weight}%)
                          </Tag>
                        ))
                      ) : (
                        <Text code>{route.uri || 'N/A'}</Text>
                      )}
                    </div>
                  </div>

                  {/* Predicates */}
                  <div className="route-section">
                    <Text type="secondary" className="section-label">
                      <BranchesOutlined /> {t('routes.predicates')} ({predicateCount})
                    </Text>
                    <div className="tags-container">
                      {predicateCount > 0 ? (
                        route.predicates!.map((p, idx) => getPredicateTag(p))
                      ) : (
                        <Text type="secondary" className="no-data">{t('routes.no_predicates')}</Text>
                      )}
                    </div>
                  </div>

                  {/* Plugins */}
                  <div className="route-section">
                    <Text type="secondary" className="section-label">
                      <ThunderboltOutlined /> {t('routes.plugins')} ({pluginCount})
                    </Text>
                    <div className="tags-container">
                      {pluginCount > 0 ? (
                        route.filters!.map((f, idx) => (
                          <Tooltip key={idx} title={t(`plugin.${f.name}.detail`, { defaultValue: '' })} placement="top">
                            <Tag className="plugin-tag">
                              <ThunderboltOutlined /> {t(`plugin.${f.name}.name`, { defaultValue: f.name })}
                            </Tag>
                          </Tooltip>
                        ))
                      ) : (
                        <Text type="secondary" className="no-data">{t('routes.no_plugins')}</Text>
                      )}
                    </div>
                  </div>

                  {/* Meta */}
                  <div className="route-meta">
                    <Text type="secondary">Order: {route.order ?? 0}</Text>
                    {route.description && (
                      <Tooltip title={route.description}>
                        <Text type="secondary" ellipsis className="route-desc">{route.description}</Text>
                      </Tooltip>
                    )}
                  </div>
                </Card>
              );
            })}
            </div>
            {/* Pagination */}
            {filteredRoutes.length > pageSize && (
              <div className="pagination-container">
                <Pagination
                  current={currentPage}
                  pageSize={pageSize}
                  total={filteredRoutes.length}
                  onChange={(page, size) => {
                    setCurrentPage(page);
                    setPageSize(size);
                  }}
                  showSizeChanger
                  showQuickJumper
                  showTotal={(total) => t('routes.pagination_total', { total })}
                  pageSizeOptions={['12', '24', '48', '96']}
                />
              </div>
            )}
          </>
        )}
      </Spin>

      {/* Create Route Modal */}
      <Modal 
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper">
                <PlusOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('routes.create')}</div>
                <div className="modal-subtitle">{t('routes.create_description')}</div>
              </div>
            </div>
          </div>
        }
        open={createModalVisible}
        onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); }}
        footer={null}
        width={720}
        className="route-modal route-create-modal"
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate} initialValues={{ order: 0, enabled: true, routingMode: 'SINGLE' }}>
          {/* Basic Info Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <FileTextOutlined className="section-icon" />
              <span className="section-title">{t('routes.basic_info')}</span>
            </div>
            <div className="section-content">
              <Form.Item name="id" label={t('routes.route_id_label')} rules={[{ required: true }]} extra={t('routes.route_id_helper')}>
                <Input placeholder={t('routes.route_id_placeholder')} size="large" />
              </Form.Item>
              <div className="form-row">
                <Form.Item name="order" label={t('routes.order')} extra={t('routes.order_helper')} className="form-item-half">
                  <Input type="number" placeholder="0" size="large" />
                </Form.Item>
                <Form.Item name="enabled" label={t('routes.enabled')} valuePropName="checked" className="form-item-half" style={{ paddingTop: '24px' }}>
                  <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                </Form.Item>
              </div>
              <Form.Item name="description" label={t('routes.description_label')} extra={t('routes.description_helper')}>
                <TextArea rows={2} placeholder={t('routes.description_placeholder')} size="large" showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Target Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('routes.target_configuration')}</span>
              <span className="section-subtitle">{t('routes.target_helper')}</span>
            </div>
            <div className="section-content">
              {/* Routing Mode Selection */}
              <Form.Item name="routingMode" label={t('routes.routing_mode_label')} rules={[{ required: true }]} extra={t('routes.routing_mode_helper')}>
                <Radio.Group buttonStyle="solid" onChange={(e) => {
                  const newMode = e.target.value;
                  setRoutingMode(newMode);
                  createForm.setFieldsValue({ serviceId: undefined, services: [], uri: '' });
                }} size="large">
                  <Radio.Button value="SINGLE"><CloudOutlined /> {t('routes.single_service')}</Radio.Button>
                  <Radio.Button value="MULTI"><SplitCellsOutlined /> {t('routes.multi_service')}</Radio.Button>
                </Radio.Group>
              </Form.Item>

              {/* Single Service Mode */}
              {routingMode === 'SINGLE' && (
                <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]} extra={t('routes.select_service')}>
                  <Select placeholder={t('routes.select_service')} size="large" onChange={(value) => {
                    // value is already in format: "static://serviceId" or "lb://serviceName"
                    createForm.setFieldValue('uri', value || '');
                  }} showSearch>
                    <Select.OptGroup label={<><CloudOutlined /> {t('routes.static_services')}</>}>
                      {services.map(s => (
                        <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                          {s.name || s.serviceId} <Text type="secondary">(Static)</Text>
                        </Select.Option>
                      ))}
                    </Select.OptGroup>
                    <Select.OptGroup label={<><GlobalOutlined /> {t('routes.nacos_services')}</>}>
                      {nacosServices.map(s => (
                        <Select.Option key={`lb-${s.serviceName}`} value={`lb://${s.serviceName}`}>
                          {s.serviceName} <Text type="secondary">(Nacos)</Text>
                        </Select.Option>
                      ))}
                    </Select.OptGroup>
                  </Select>
                </Form.Item>
              )}

              {/* Multi-Service Mode */}
              {routingMode === 'MULTI' && (
                <>
                  <div style={{ marginBottom: '12px' }}>
                    <Text type="secondary">{t('routes.multi_service_helper')}</Text>
                  </div>
                  <Form.List name="services" initialValue={[{ weight: 100, enabled: true }]}>
                    {(fields, { add, remove }) => (
                      <>
                        {fields.map(({ key, name, ...restField }) => (
                          <div key={key} style={{
                            padding: '12px',
                            marginBottom: '8px',
                            background: '#fafafa',
                            borderRadius: '6px',
                            border: '1px solid #e8e8e8'
                          }}>
                            <div style={{ display: 'flex', gap: '12px', alignItems: 'flex-start', flexWrap: 'wrap' }}>
                              <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                                <Select placeholder={t('routes.select_service')} style={{ width: 240 }} showSearch>
                                  <Select.OptGroup label={<><CloudOutlined /> {t('routes.static_services')}</>}>
                                    {services.map(s => (
                                      <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                                        {s.name || s.serviceId} <Text type="secondary">(Static)</Text>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                  <Select.OptGroup label={<><GlobalOutlined /> {t('routes.nacos_services')}</>}>
                                    {nacosServices.map(s => (
                                      <Select.Option key={`lb-${s.serviceName}`} value={`lb://${s.serviceName}`}>
                                        {s.serviceName} <Text type="secondary">(Nacos)</Text>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                </Select>
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                                <InputNumber
                                  min={1} max={100}
                                  placeholder="Weight"
                                  style={{ width: 100 }}
                                  addonAfter="%"
                                />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'version']} noStyle>
                                <Input placeholder={t('routes.version_placeholder')} style={{ width: 120 }} />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked" initialValue={true}>
                                <Switch size="small" checkedChildren="ON" unCheckedChildren="OFF" />
                              </Form.Item>
                              {fields.length > 1 && (
                                <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                              )}
                            </div>
                          </div>
                        ))}
                        <Button type="dashed" onClick={() => add({ weight: 100, enabled: true })} block icon={<PlusOutlined />}>
                          {t('routes.add_service_binding')}
                        </Button>
                      </>
                    )}
                  </Form.List>

                  {/* Gray Rules Section */}
                  <Divider />
                  <div style={{ marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <Switch
                      checked={grayRulesEnabled}
                      onChange={setGrayRulesEnabled}
                      checkedChildren={t('routes.gray_rules_enabled')}
                      unCheckedChildren={t('routes.gray_rules_disabled')}
                    />
                    <Text type="secondary">{t('routes.gray_rules_helper')}</Text>
                  </div>

                  {grayRulesEnabled && (
                    <Form.List name="grayRules">
                      {(fields, { add, remove }) => (
                        <>
                          {fields.map(({ key, name, ...restField }) => {
                            const ruleType = createForm.getFieldValue(['grayRules', name, 'type']) || 'HEADER';
                            return (
                              <div key={key} style={{
                                padding: '12px',
                                marginBottom: '8px',
                                background: '#fff7e6',
                                borderRadius: '6px',
                                border: '1px solid #ffd591'
                              }}>
                                <div style={{ display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap' }}>
                                  <Form.Item {...restField} name={[name, 'type']} noStyle initialValue="HEADER">
                                    <Select style={{ width: 100 }} placeholder={t('routes.rule_type')}>
                                      <Select.Option value="HEADER">Header</Select.Option>
                                      <Select.Option value="COOKIE">Cookie</Select.Option>
                                      <Select.Option value="QUERY">Query</Select.Option>
                                      <Select.Option value="WEIGHT">Weight</Select.Option>
                                    </Select>
                                  </Form.Item>
                                  {ruleType !== 'WEIGHT' && (
                                    <Form.Item {...restField} name={[name, 'name']} noStyle>
                                      <Input placeholder={t('routes.gray_rule_name')} style={{ width: 120 }} />
                                    </Form.Item>
                                  )}
                                  <Form.Item {...restField} name={[name, 'value']} noStyle>
                                    {ruleType === 'WEIGHT' ? (
                                      <InputNumber min={1} max={100} placeholder="%" style={{ width: 80 }} addonAfter="%" />
                                    ) : (
                                      <Input placeholder={t('routes.gray_rule_value')} style={{ width: 120 }} />
                                    )}
                                  </Form.Item>
                                  <Form.Item {...restField} name={[name, 'targetVersion']} noStyle>
                                    <Input placeholder={t('routes.target_version')} style={{ width: 120 }} />
                                  </Form.Item>
                                  <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                                </div>
                              </div>
                            );
                          })}
                          <Button type="dashed" onClick={() => add({ type: 'HEADER' })} block icon={<PlusOutlined />}>
                            {t('routes.add_gray_rule')}
                          </Button>
                        </>
                      )}
                    </Form.List>
                  )}
                </>
              )}

              {/* URI Display (for backward compatibility) */}
              {routingMode === 'SINGLE' && (
                <Form.Item name="uri" label={t('routes.target_uri')} extra={t('routes.uri_auto_generated')}>
                  <Input placeholder={t('routes.uri_placeholder')} disabled size="large" />
                </Form.Item>
              )}
            </div>
          </div>

          {/* Predicates Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ThunderboltOutlined className="section-icon" />
              <span className="section-title">{t('routes.predicates_section')}</span>
              <span className="section-subtitle">{t('routes.at_least_one_path')}</span>
            </div>
            <div className="section-content">
              <Form.List name="predicates">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name, ...restField }) => (
                      <PredicateItem key={key} form={createForm} restField={restField} name={name} t={t} onRemove={remove} />
                    ))}
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" className="add-predicate-btn-modern">
                      {t('routes.add_predicate')}
                    </Button>
                  </>
                )}
              </Form.List>
            </div>
          </div>

          {/* Filters Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <FilterOutlined className="section-icon" />
              <span className="section-title">{t('routes.filters_section')}</span>
              <span className="section-subtitle">{t('routes.filters_helper')}</span>
            </div>
            <div className="section-content">
              <Form.List name="filters">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name, ...restField }) => (
                      <FilterItem key={key} form={createForm} restField={restField} name={name} t={t} onRemove={remove} />
                    ))}
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" className="add-filter-btn-modern">
                      {t('routes.add_plugin')}
                    </Button>
                  </>
                )}
              </Form.List>
            </div>
          </div>

          {/* Modal Footer */}
          <div className="modal-footer-modern">
            <Button onClick={() => setCreateModalVisible(false)} size="large">{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" size="large" icon={<PlusOutlined />}>{t('routes.create')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Edit Route Modal */}
      <Modal 
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper edit">
                <EditOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('routes.edit')}</div>
                <div className="modal-subtitle">{t('routes.edit_description')}</div>
              </div>
            </div>
          </div>
        }
        open={editModalVisible}
        onCancel={() => { setEditModalVisible(false); editForm.resetFields(); }}
        footer={null}
        width={720}
        className="route-modal route-edit-modal"
      >
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          <Form.Item name="id" hidden><Input /></Form.Item>

          {/* Basic Info Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <FileTextOutlined className="section-icon" />
              <span className="section-title">{t('routes.basic_info')}</span>
            </div>
            <div className="section-content">
              <Form.Item name="routeName" label={t('routes.route_name')} extra={t('routes.route_name_readonly')}>
                <Input disabled size="large" />
              </Form.Item>
              <div className="form-row">
                <Form.Item name="order" label={t('routes.order')} extra={t('routes.order_helper')} className="form-item-half">
                  <Input type="number" placeholder="0" size="large" />
                </Form.Item>
                <Form.Item name="enabled" label={t('routes.enabled')} valuePropName="checked" className="form-item-half" style={{ paddingTop: '24px' }}>
                  <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                </Form.Item>
              </div>
              <Form.Item name="description" label={t('routes.description_label')} extra={t('routes.description_helper')}>
                <TextArea rows={2} placeholder={t('routes.description_placeholder')} size="large" showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Target Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('routes.target_configuration')}</span>
              <span className="section-subtitle">{t('routes.target_helper')}</span>
            </div>
            <div className="section-content">
              {/* Routing Mode Selection */}
              <Form.Item name="routingMode" label={t('routes.routing_mode_label')} rules={[{ required: true }]}>
                <Radio.Group buttonStyle="solid" onChange={(e) => {
                  const newMode = e.target.value;
                  setEditRoutingMode(newMode);
                  editForm.setFieldsValue({ serviceId: undefined, services: [], uri: '' });
                }} size="large">
                  <Radio.Button value="SINGLE"><CloudOutlined /> {t('routes.single_service')}</Radio.Button>
                  <Radio.Button value="MULTI"><SplitCellsOutlined /> {t('routes.multi_service')}</Radio.Button>
                </Radio.Group>
              </Form.Item>

              {/* Single Service Mode */}
              {editRoutingMode === 'SINGLE' && (
                <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]} extra={t('routes.select_service')}>
                  <Select placeholder={t('routes.select_service')} size="large" onChange={(value) => {
                    editForm.setFieldValue('uri', value ? `static://${value}` : '');
                  }} showSearch>
                    {services.map(s => (
                      <Select.Option key={s.serviceId} value={s.serviceId}>{s.serviceId} ({s.name})</Select.Option>
                    ))}
                  </Select>
                </Form.Item>
              )}

              {/* Multi-Service Mode */}
              {editRoutingMode === 'MULTI' && (
                <>
                  <div style={{ marginBottom: '12px' }}>
                    <Text type="secondary">{t('routes.multi_service_helper')}</Text>
                  </div>
                  <Form.List name="services">
                    {(fields, { add, remove }) => (
                      <>
                        {fields.map(({ key, name, ...restField }) => (
                          <div key={key} style={{
                            padding: '12px',
                            marginBottom: '8px',
                            background: '#fafafa',
                            borderRadius: '6px',
                            border: '1px solid #e8e8e8'
                          }}>
                            <div style={{ display: 'flex', gap: '12px', alignItems: 'flex-start', flexWrap: 'wrap' }}>
                              <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                                <Select placeholder={t('routes.select_service')} style={{ width: 240 }} showSearch>
                                  <Select.OptGroup label={<><CloudOutlined /> {t('routes.static_services')}</>}>
                                    {services.map(s => (
                                      <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                                        {s.name || s.serviceId} <Text type="secondary">(Static)</Text>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                  <Select.OptGroup label={<><GlobalOutlined /> {t('routes.nacos_services')}</>}>
                                    {nacosServices.map(s => (
                                      <Select.Option key={`lb-${s.serviceName}`} value={`lb://${s.serviceName}`}>
                                        {s.serviceName} <Text type="secondary">(Nacos)</Text>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                </Select>
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                                <InputNumber min={1} max={100} placeholder="Weight" style={{ width: 100 }} addonAfter="%" />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'version']} noStyle>
                                <Input placeholder={t('routes.version_placeholder')} style={{ width: 120 }} />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked" initialValue={true}>
                                <Switch size="small" checkedChildren="ON" unCheckedChildren="OFF" />
                              </Form.Item>
                              {fields.length > 1 && (
                                <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                              )}
                            </div>
                          </div>
                        ))}
                        <Button type="dashed" onClick={() => add({ weight: 100, enabled: true })} block icon={<PlusOutlined />}>
                          {t('routes.add_service_binding')}
                        </Button>
                      </>
                    )}
                  </Form.List>

                  {/* Gray Rules Section */}
                  <Divider />
                  <div style={{ marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <Switch
                      checked={editGrayRulesEnabled}
                      onChange={setEditGrayRulesEnabled}
                      checkedChildren={t('routes.gray_rules_enabled')}
                      unCheckedChildren={t('routes.gray_rules_disabled')}
                    />
                    <Text type="secondary">{t('routes.gray_rules_helper')}</Text>
                  </div>

                  {editGrayRulesEnabled && (
                    <Form.List name="grayRules">
                      {(fields, { add, remove }) => (
                        <>
                          {fields.map(({ key, name, ...restField }) => {
                            const ruleType = editForm.getFieldValue(['grayRules', name, 'type']) || 'HEADER';
                            return (
                              <div key={key} style={{
                                padding: '12px',
                                marginBottom: '8px',
                                background: '#fff7e6',
                                borderRadius: '6px',
                                border: '1px solid #ffd591'
                              }}>
                                <div style={{ display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap' }}>
                                  <Form.Item {...restField} name={[name, 'type']} noStyle initialValue="HEADER">
                                    <Select style={{ width: 100 }} placeholder={t('routes.rule_type')}>
                                      <Select.Option value="HEADER">Header</Select.Option>
                                      <Select.Option value="COOKIE">Cookie</Select.Option>
                                      <Select.Option value="QUERY">Query</Select.Option>
                                      <Select.Option value="WEIGHT">Weight</Select.Option>
                                    </Select>
                                  </Form.Item>
                                  {ruleType !== 'WEIGHT' && (
                                    <Form.Item {...restField} name={[name, 'name']} noStyle>
                                      <Input placeholder={t('routes.gray_rule_name')} style={{ width: 120 }} />
                                    </Form.Item>
                                  )}
                                  <Form.Item {...restField} name={[name, 'value']} noStyle>
                                    {ruleType === 'WEIGHT' ? (
                                      <InputNumber min={1} max={100} placeholder="%" style={{ width: 80 }} addonAfter="%" />
                                    ) : (
                                      <Input placeholder={t('routes.gray_rule_value')} style={{ width: 120 }} />
                                    )}
                                  </Form.Item>
                                  <Form.Item {...restField} name={[name, 'targetVersion']} noStyle>
                                    <Input placeholder={t('routes.target_version')} style={{ width: 120 }} />
                                  </Form.Item>
                                  <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                                </div>
                              </div>
                            );
                          })}
                          <Button type="dashed" onClick={() => add({ type: 'HEADER' })} block icon={<PlusOutlined />}>
                            {t('routes.add_gray_rule')}
                          </Button>
                        </>
                      )}
                    </Form.List>
                  )}
                </>
              )}

              {/* URI Display (for backward compatibility) */}
              {editRoutingMode === 'SINGLE' && (
                <Form.Item name="uri" label={t('routes.target_uri')} extra={t('routes.uri_auto_generated')}>
                  <Input disabled size="large" />
                </Form.Item>
              )}
            </div>
          </div>

          {/* Predicates Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ThunderboltOutlined className="section-icon" />
              <span className="section-title">{t('routes.predicates_section')}</span>
              <span className="section-subtitle">{t('routes.at_least_one_path')}</span>
            </div>
            <div className="section-content">
              <Form.List name="predicates">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name, ...restField }) => (
                      <PredicateItem key={key} form={editForm} restField={restField} name={name} t={t} onRemove={remove} />
                    ))}
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" className="add-predicate-btn-modern">
                      {t('routes.add_predicate')}
                    </Button>
                  </>
                )}
              </Form.List>
            </div>
          </div>

          {/* Filters Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <FilterOutlined className="section-icon" />
              <span className="section-title">{t('routes.filters_section')}</span>
              <span className="section-subtitle">{t('routes.filters_helper')}</span>
            </div>
            <div className="section-content">
              <Form.List name="filters">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name, ...restField }) => (
                      <FilterItem key={key} form={editForm} restField={restField} name={name} t={t} onRemove={remove} />
                    ))}
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" className="add-filter-btn-modern">
                      {t('routes.add_plugin')}
                    </Button>
                  </>
                )}
              </Form.List>
            </div>
          </div>

          {/* Modal Footer */}
          <div className="modal-footer-modern">
            <Button onClick={() => setEditModalVisible(false)} size="large">{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" size="large" icon={<EditOutlined />}>{t('common.update')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Route Detail Modal */}
      <Modal
        title={<div className="modal-header"><EyeOutlined className="modal-icon" /><span>{t('routes.detail_title')}</span></div>}
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={null}
        width={640}
        className="route-modal"
      >
        {selectedRoute && (
          <div className="route-detail">
            <div className="detail-item">
              <Text type="secondary">{t('routes.name')}</Text>
              <Text strong>{selectedRoute.routeName}</Text>
            </div>
            <div className="detail-item">
              <Text type="secondary">{t('routes.id')}</Text>
              <Space>
                <Text code>{selectedRoute.id}</Text>
                <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => copyToClipboard(selectedRoute.id, t('routes.id'))} />
              </Space>
            </div>
            <div className="detail-item">
              <Text type="secondary">{t('routes.uri')}</Text>
              <Text code>{selectedRoute.uri}</Text>
            </div>
            <div className="detail-item">
              <Text type="secondary">{t('routes.order')}</Text>
              <Text>{selectedRoute.order ?? '-'}</Text>
            </div>
            <div className="detail-item">
              <Text type="secondary">{t('routes.enabled')}</Text>
              <Tag color={selectedRoute.enabled ? 'success' : 'default'}>
                {selectedRoute.enabled ? t('common.enabled') : t('common.disabled')}
              </Tag>
            </div>
            {selectedRoute.description && (
              <div className="detail-item">
                <Text type="secondary">{t('routes.description')}</Text>
                <Text>{selectedRoute.description}</Text>
              </div>
            )}

            {selectedRoute.predicates && selectedRoute.predicates.length > 0 && (
              <div className="detail-section">
                <h4>{t('routes.predicates')}</h4>
                {selectedRoute.predicates.map((p, idx) => (
                  <Card key={idx} size="small" className="detail-card">
                    <Text strong>{p.name}</Text>
                    <pre className="detail-json">{JSON.stringify(p.args, null, 2)}</pre>
                  </Card>
                ))}
              </div>
            )}

            {selectedRoute.filters && selectedRoute.filters.length > 0 && (
              <div className="detail-section">
                <h4>{t('routes.plugins')}</h4>
                {selectedRoute.filters.map((f, idx) => (
                  <Card key={idx} size="small" className="detail-card">
                    <Text strong>{t(`plugin.${f.name}.name`, { defaultValue: f.name })}</Text>
                    <pre className="detail-json">{JSON.stringify(f.args, null, 2)}</pre>
                  </Card>
                ))}
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
};

export default RoutesPage;