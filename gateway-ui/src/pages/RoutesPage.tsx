import { useState, useEffect, useCallback, memo, useMemo, useRef } from 'react';
import {
  Card, Button, Space, message, Spin, Tag, Modal, Form, Input, Switch,
  Select, Empty, Radio, Tooltip, Badge, Divider, Typography, Dropdown, Pagination,
  InputNumber, Slider, Collapse, Drawer, Popconfirm
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EyeOutlined, CopyOutlined, StopOutlined,
  PlayCircleOutlined, EditOutlined, CompassOutlined, MoreOutlined,
  ApiOutlined, BranchesOutlined, ThunderboltOutlined, FileTextOutlined,
  CloudOutlined, GlobalOutlined, FilterOutlined, SplitCellsOutlined, CloseOutlined,
  SearchOutlined, QuestionCircleOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import copy from 'copy-to-clipboard';

const { Text, Title } = Typography;
const { TextArea } = Input;

// Routing mode enum - matches backend
type RoutingMode = 'SINGLE' | 'MULTI';

// Service binding type - matches backend ServiceBindingType enum
type ServiceBindingType = 'STATIC' | 'DISCOVERY';

// Route service binding - matches backend RouteServiceBinding
interface RouteServiceBinding {
  serviceId: string;
  serviceName: string;
  weight: number;
  version?: string;
  enabled: boolean;
  description?: string;
  type?: ServiceBindingType; // STATIC or DISCOVERY, defaults to STATIC
  serviceNamespace?: string; // Nacos namespace (only for DISCOVERY type)
  serviceGroup?: string;     // Nacos group (only for DISCOVERY type)
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
  serviceNamespace?: string; // Nacos namespace (only for lb:// type)
  serviceGroup?: string;     // Nacos group (only for lb:// type)
  services?: RouteServiceBinding[];
  grayRules?: GrayRules;
  predicates?: any[];
  filters?: any[];
  order?: number;
  enabled?: boolean;
  description?: string;
  metadata?: any;
}

// Separate component for route action dropdown to prevent re-renders
interface RouteActionDropdownProps {
  route: Route;
  onEdit: (route: Route) => void;
  onDetail: (route: Route) => void;
  onToggle: (route: Route) => void;
  onDelete: (route: Route) => void;
}

const RouteActionDropdown = memo(({ route, onEdit, onDetail, onToggle, onDelete }: RouteActionDropdownProps) => {
  const { t } = useTranslation();

  const handleEdit = useCallback(() => onEdit(route), [onEdit, route]);
  const handleDetail = useCallback(() => onDetail(route), [onDetail, route]);
  const handleToggle = useCallback(() => onToggle(route), [onToggle, route]);
  const handleDelete = useCallback(() => onDelete(route), [onDelete, route]);

  return (
    <Space>
      <Tooltip title={t('common.edit')}>
        <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={handleEdit} />
      </Tooltip>
      <Tooltip title={t('common.detail')}>
        <Button type="text" size="small" icon={<EyeOutlined />} className="action-btn" onClick={handleDetail} />
      </Tooltip>
      <Tooltip title={route.enabled ? t('common.disable') : t('common.enable')}>
        <Button type="text" size="small" icon={route.enabled ? <StopOutlined /> : <PlayCircleOutlined />} className="action-btn" onClick={handleToggle} />
      </Tooltip>
      <Popconfirm
        title={`${t('common.delete')}: ${route.routeName || route.id}`}
        onConfirm={handleDelete}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Tooltip title={t('common.delete')}>
          <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
        </Tooltip>
      </Popconfirm>
    </Space>
  );
});

interface Service {
  name: string;
  serviceId: string;
  loadBalancer: string;
  instances?: any[];
}

interface NacosService {
  serviceName: string;
  namespace?: string;
  group?: string;
  instanceCount?: number;
  displayName?: string;
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
          style={{ 
            width: 150,
            backgroundColor: 'rgba(0, 0, 0, 0.3)',
            border: '1px solid rgba(148, 163, 184, 0.15)'
          }}
          onChange={(value) => setPredicateType(value)}
          suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
        >
          <Select.Option value="Path">Path</Select.Option>
          <Select.Option value="Host">Host</Select.Option>
          <Select.Option value="Method">Method</Select.Option>
          <Select.Option value="Header">Header</Select.Option>
        </Select>
      </Form.Item>
      <Form.Item {...restField} name={[name, 'args']} noStyle>
        {predicateType === 'Method' ? (
          <Select 
            placeholder={t('routes.arguments')} 
            style={{ 
              width: 300,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)'
            }} 
            mode="multiple"
            suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
          >
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
              style={{ 
                width: '45%',
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['predicates', name, 'args']);
                const newValue = `${e.target.value}:${currentValue?.split(':')[1] || ''}`;
                form.setFieldValue(['predicates', name, 'args'], newValue);
              }}
            />
            <Input
              placeholder="Header Value"
              style={{ 
                width: '55%',
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['predicates', name, 'args']);
                const newValue = `${currentValue?.split(':')[0] || ''}:${e.target.value}`;
                form.setFieldValue(['predicates', name, 'args'], newValue);
              }}
            />
          </Space.Compact>
        ) : (
          <Input 
            placeholder={t('routes.arguments')} 
            style={{ 
              width: 300,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)',
              color: '#e2e8f0'
            }} 
          />
        )}
      </Form.Item>
      <Tooltip title={t('common.delete')}>
        <Button 
          type="text" 
          onClick={() => onRemove(name)} 
          icon={<DeleteOutlined />}
          style={{ 
            color: '#ef4444',
            opacity: 0.8,
            padding: '4px 8px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}
          onMouseEnter={(e) => e.currentTarget.style.opacity = '1'}
          onMouseLeave={(e) => e.currentTarget.style.opacity = '0.8'}
        />
      </Tooltip>
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

  // Filters that need name:value input (colon separated)
  const needsKeyValueInput = [
    'AddRequestHeader', 'SetRequestHeader', 'AddRequestParameter',
    'AddResponseHeader', 'SetResponseHeader'
  ];

  // Filters that only need a single name input
  const needsNameOnly = [
    'RemoveRequestHeader', 'RemoveResponseHeader', 'RemoveRequestParameter'
  ];

  // Special handling for RewritePath (regexp,replacement with comma)
  const isRewritePath = filterType === 'RewritePath';

  // Special handling for RedirectTo (status,url with comma)
  const isRedirectTo = filterType === 'RedirectTo';

  // SecureHeaders has no required parameters
  const noParamsNeeded = filterType === 'SecureHeaders';

  return (
    <div key={name} className="form-item-row">
      <Tooltip 
        title={filterType ? t(`plugin.${filterType}.detail`) : ''} 
        placement="topLeft" 
        mouseEnterDelay={0}
        overlayInnerStyle={{ 
          maxWidth: '350px',
          fontSize: '13px',
          lineHeight: '1.6',
          padding: '10px 14px'
        }}
      >
        <Form.Item {...restField} name={[name, 'name']} noStyle>
          <Select
            placeholder={t('routes.filter_type')}
            className="plugin-type-select"
            style={{ 
              width: 160,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)'
            }}
            onChange={(value) => setFilterType(value)}
            suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
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
      </Tooltip>
      <Form.Item {...restField} name={[name, 'args']} noStyle>
        {needsKeyValueInput.includes(filterType) ? (
          // name:value format (colon separated) - 分开显示
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <Input
              placeholder="Name"
              style={{ 
                width: 110,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${e.target.value}:${currentValue?.split(':')[1] || ''}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
            <span style={{ color: '#64748b' }}>:</span>
            <Input
              placeholder="Value"
              style={{ 
                width: 110,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${currentValue?.split(':')[0] || ''}:${e.target.value}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
          </div>
        ) : needsNameOnly.includes(filterType) ? (
          // Single name input only
          <Input 
            placeholder="Header/Parameter Name" 
            style={{ 
              width: 240,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)',
              color: '#e2e8f0'
            }} 
          />
        ) : isRewritePath ? (
          // regexp,replacement format (comma separated) - SCG standard
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <Input
              placeholder="Regexp"
              style={{ 
                width: 110,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${e.target.value},${currentValue?.split(',')[1] || ''}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
            <span style={{ color: '#64748b' }}>→</span>
            <Input
              placeholder="Replacement"
              style={{ 
                width: 110,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${currentValue?.split(',')[0] || ''},${e.target.value}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
          </div>
        ) : isRedirectTo ? (
          // status,url format (comma separated) - SCG standard
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <Select
              placeholder="Status"
              style={{ 
                width: 80,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)'
              }}
              onChange={(value) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${value},${currentValue?.split(',')[1] || ''}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
              suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
            >
              <Select.Option value="301">301</Select.Option>
              <Select.Option value="302">302</Select.Option>
              <Select.Option value="303">303</Select.Option>
              <Select.Option value="307">307</Select.Option>
              <Select.Option value="308">308</Select.Option>
            </Select>
            <Input
              placeholder="URL"
              style={{ 
                width: 150,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                const currentValue = form.getFieldValue(['filters', name, 'args']);
                const newValue = `${currentValue?.split(',')[0] || ''},${e.target.value}`;
                form.setFieldValue(['filters', name, 'args'], newValue);
              }}
            />
          </div>
        ) : noParamsNeeded ? (
          // No parameters needed
          <Input 
            placeholder="(No parameters required)" 
            style={{ 
              width: 240,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)',
              color: '#e2e8f0'
            }} 
            disabled 
          />
        ) : (
          <Input 
            placeholder={t('routes.arguments')} 
            style={{ 
              width: 240,
              backgroundColor: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(148, 163, 184, 0.15)',
              color: '#e2e8f0'
            }} 
            disabled={!filterType} 
          />
        )}
      </Form.Item>
      {filterType && (
        <Tooltip 
          title={t(`plugin.${filterType}.detail`)} 
          placement="top" 
          mouseEnterDelay={0}
          overlayInnerStyle={{ 
            maxWidth: '350px',
            fontSize: '13px',
            lineHeight: '1.6',
            padding: '10px 14px'
          }}
          className="plugin-tooltip"
        >
          <QuestionCircleOutlined 
            className="plugin-help-icon"
          />
        </Tooltip>
      )}
      <Tooltip title={t('common.delete')}>
        <Button 
          type="text" 
          onClick={() => onRemove(name)} 
          icon={<DeleteOutlined />}
          style={{ 
            color: '#ef4444',
            opacity: 0.8,
            padding: '4px 8px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}
          onMouseEnter={(e) => e.currentTarget.style.opacity = '1'}
          onMouseLeave={(e) => e.currentTarget.style.opacity = '0.8'}
        />
      </Tooltip>
    </div>
  );
};

interface RoutesPageProps {
  instanceId?: string;
}

const RoutesPage: React.FC<RoutesPageProps> = ({ instanceId }) => {
  const [routes, setRoutes] = useState<Route[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [nacosServices, setNacosServices] = useState<NacosService[]>([]);
  // Use ref to store nacosServices for stable callback references
  const nacosServicesRef = useRef<NacosService[]>(nacosServices);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
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
  // Store edit services list for gray rules dropdown (more reliable than Form.useWatch)
  const [editServicesList, setEditServicesList] = useState<any[]>([]);
  const { t } = useTranslation();

  // Watch services list for gray rules dropdown (reactive updates) - for create form only
  const createFormServices = Form.useWatch('services', createForm);

  useEffect(() => {
    loadRoutes();
    loadServices();
    loadNacosServices();
  }, [instanceId]);

  // Reset to first page when search/filter changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchTerm, statusFilter]);

  // Keep ref in sync with nacosServices state for stable callback references
  useEffect(() => {
    nacosServicesRef.current = nacosServices;
  }, [nacosServices]);

  const loadRoutes = useCallback(async () => {
    try {
      setLoading(true);
      const params = instanceId ? { instanceId } : {};
      const response = await api.get('/api/routes', { params });
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
  }, [t, instanceId]);

  const loadServices = async () => {
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.get('/api/services', { params });
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
        const nacosServiceList: NacosService[] = response.data.data || [];
        setNacosServices(nacosServiceList);
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
  const disabledRoutes = routes.filter(r => !r.enabled).length;
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
    // Note: id (UUID) is generated by backend, routeName is the business name
    const routeData: any = {
      routeName: values.id,  // User input route name as business identifier
      order: values.order || 0,
      enabled: values.enabled !== false,
      description: values.description,
      predicates: formattedPredicates,
      filters: formattedFilters,
      mode: routingMode
    };

    if (routingMode === 'SINGLE') {
      // Single service mode - serviceId is in format: static://xxx or lb://serviceName
      routeData.uri = values.serviceId || values.uri;
      // Extract serviceId from URI for backend compatibility
      if (values.serviceId) {
        const uriParts = values.serviceId.split('://');
        if (uriParts.length === 2) {
          routeData.serviceId = uriParts[1]; // Pure service name without query params
        }
      }
      // Use namespace/group from form hidden fields (for lb:// services)
      // Always set these fields for lb:// services (even if empty string for public namespace)
      if (values.serviceId && values.serviceId.startsWith('lb://')) {
        routeData.serviceNamespace = values.serviceNamespace || '';
        routeData.serviceGroup = values.serviceGroup || 'DEFAULT_GROUP';
      }
    } else {
      // Multi-service mode
      const serviceBindings = values.services || [];
      if (serviceBindings.length === 0) {
        message.error(t('routes.at_least_one_service'));
        return;
      }

      // Validate total weight equals 100%
      const totalWeight = serviceBindings.reduce((sum: number, s: any) => sum + (s.weight || 0), 0);
      if (totalWeight !== 100) {
        message.error(t('routes.weight_must_be_100', { total: totalWeight }));
        return;
      }

      routeData.services = serviceBindings.map((s: any) => {
        // s.serviceId is in format: static://xxx or lb://serviceName
        const uriParts = (s.serviceId || '').split('://');
        const protocol = uriParts[0] || 'static';
        const serviceId = uriParts[1] || s.serviceId;

        // Derive type from protocol: static:// -> STATIC, lb:// -> DISCOVERY
        const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

        // For Nacos services (lb://), use serviceId directly as serviceName
        // For static services, look up name from services list
        const serviceName = protocol === 'lb'
          ? serviceId
          : (services.find(svc => svc.serviceId === serviceId)?.name || serviceId);

        return {
          serviceId: serviceId,
          serviceName: serviceName,
          weight: s.weight || 100,
          version: s.version,
          enabled: s.enabled !== false,
          type: bindingType,
          serviceNamespace: s.serviceNamespace,  // From form field (onChange sets it)
          serviceGroup: s.serviceGroup           // From form field (onChange sets it)
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

    const params = instanceId ? { instanceId } : {};
    api.post('/api/routes', routeData, { params })
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

    // Validate multi-service weight if in MULTI mode
    if (editRoutingMode === 'MULTI' && values.services && values.services.length > 0) {
      const totalWeight = values.services.reduce((sum: number, s: any) => sum + (s.weight || 0), 0);
      if (totalWeight !== 100) {
        message.error(t('routes.weight_must_be_100', { total: totalWeight }));
        return;
      }
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
    // Note: id (UUID) is from path parameter, routeName is the business name
    const routeData: any = {
      routeName: values.routeName,  // Business name
      order: values.order || 0,
      enabled: values.enabled !== false,
      description: values.description,
      predicates: formattedPredicates,
      filters: formattedFilters,
      mode: editRoutingMode
    };

    if (editRoutingMode === 'SINGLE') {
      // Single service mode - serviceId is in format: static://xxx or lb://serviceName
      const finalUri = values.serviceId || values.uri;
      if (!finalUri) {
        message.error(t('routes.uri_required'));
        return;
      }
      routeData.uri = finalUri;
      if (values.serviceId) {
        const uriParts = values.serviceId.split('://');
        if (uriParts.length === 2) {
          routeData.serviceId = uriParts[1];  // Pure service name
        }
      }
      // Use namespace/group from form hidden fields (for lb:// services)
      // Always set these fields for lb:// services (even if empty string for public namespace)
      if (values.serviceId && values.serviceId.startsWith('lb://')) {
        routeData.serviceNamespace = values.serviceNamespace || '';
        routeData.serviceGroup = values.serviceGroup || 'DEFAULT_GROUP';
      }
    } else {
      // Multi-service mode
      const serviceBindings = values.services || [];
      if (serviceBindings.length === 0) {
        message.error(t('routes.at_least_one_service'));
        return;
      }

      routeData.services = serviceBindings.map((s: any) => {
        const uriParts = (s.serviceId || '').split('://');
        const protocol = uriParts[0] || 'static';
        const serviceInfo = uriParts[1] || s.serviceId;
        const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

        // For Nacos services (lb://), parse serviceName?namespace=xxx&group=yyy format
        let serviceId: string;
        let serviceNamespace: string | undefined;
        let serviceGroup: string | undefined;

        if (protocol === 'lb') {
          // Parse query parameters: serviceName?namespace=xxx&group=yyy
          const [namePart, queryPart] = serviceInfo.split('?');
          serviceId = namePart || serviceInfo;
          if (queryPart) {
            const params = new URLSearchParams(queryPart);
            serviceNamespace = params.get('namespace') || undefined;
            serviceGroup = params.get('group') || 'DEFAULT_GROUP';
          }
        } else {
          serviceId = serviceInfo;
        }

        // For Nacos services (lb://), use serviceId directly as serviceName
        // For static services, look up name from services list
        const serviceName = protocol === 'lb'
          ? serviceId
          : (services.find(svc => svc.serviceId === serviceId)?.name || serviceId);

        return {
          serviceId: serviceId,
          serviceName: serviceName,
          weight: s.weight || 100,
          version: s.version,
          enabled: s.enabled !== false,
          type: bindingType,
          serviceNamespace: s.serviceNamespace,  // From form field (onChange sets it)
          serviceGroup: s.serviceGroup           // From form field (onChange sets it)
        };
      });

      // Add gray rules if enabled
      if (editGrayRulesEnabled && values.grayRules && values.grayRules.length > 0) {
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
      case 'PrefixPath':
        return { prefix: args };
      case 'AddRequestHeader':
      case 'SetRequestHeader':
      case 'AddResponseHeader':
      case 'SetResponseHeader':
        const headerParts = args.split(':');
        return { name: headerParts[0] || '', value: headerParts[1] || '' };
      case 'RemoveRequestHeader':
      case 'RemoveResponseHeader':
        return { name: args };
      case 'AddRequestParameter':
        const paramParts = args.split(':');
        return { name: paramParts[0] || '', value: paramParts[1] || '' };
      case 'RemoveRequestParameter':
        return { name: args };
      case 'SetPath':
        return { template: args };
      case 'RewritePath':
        const rewriteParts = args.split(',');
        return { regexp: rewriteParts[0] || '', replacement: rewriteParts[1] || '' };
      case 'SetStatus':
        return { status: args };
      case 'RequestSize':
        return { maxSize: args };
      case 'RedirectTo':
        const redirectParts = args.split(',');
        return { status: redirectParts[0] || '302', url: redirectParts[1] || '' };
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
      case 'PrefixPath':
        return args.prefix || '';
      case 'AddRequestHeader':
      case 'SetRequestHeader':
      case 'AddResponseHeader':
      case 'SetResponseHeader':
        return `${args.name || ''}:${args.value || ''}`;
      case 'RemoveRequestHeader':
      case 'RemoveResponseHeader':
        return args.name || '';
      case 'AddRequestParameter':
        return `${args.name || ''}:${args.value || ''}`;
      case 'RemoveRequestParameter':
        return args.name || '';
      case 'SetPath':
        return args.template || '';
      case 'RewritePath':
        return `${args.regexp || ''},${args.replacement || ''}`;
      case 'SetStatus':
        return args.status || '';
      case 'RequestSize':
        return args.maxSize || args.value || '';
      case 'RedirectTo':
        return `${args.status || '302'},${args.url || ''}`;
      default:
        return args.value || '';
    }
  };

  const showRouteDetail = useCallback((record: Route) => {
    setSelectedRoute(record);
    setDetailDrawerVisible(true);
  }, []);

  const showRouteEdit = useCallback((record: Route) => {
    // 先关闭详情 Drawer
    setDetailDrawerVisible(false);
    setSelectedRoute(null);

    // Extract full URI (with protocol) for serviceId field
    // The Select component expects values like "static://serviceId" or "lb://serviceName"
    let serviceIdForForm = record.uri || '';

    const editPredicates = (record.predicates || []).map((p: any) => ({
      name: p.name,
      args: stringifyPredicateArgs(p.name, p.args)
    }));

    const editFilters = (record.filters || []).map((f: any) => ({
      name: f.name,
      args: stringifyFilterArgs(f.name, f.args)
    }));

    // Process services for multi-service mode - ensure serviceId has protocol prefix
    // Infer type from nacosServices list, NOT from stored type field (which may be wrong)
    // Use ref to avoid dependency on nacosServices state
    const editServices = (record.services || []).map((s: any) => {
      // If serviceId already has protocol, use it
      if (s.serviceId && s.serviceId.includes('://')) {
        return s;
      }
      // Infer protocol from nacosServices list (more reliable than stored type)
      const isNacosService = nacosServicesRef.current.some(ns => ns.serviceName === s.serviceId);
      const protocol = isNacosService ? 'lb' : 'static';
      return { ...s, serviceId: `${protocol}://${s.serviceId}` };
    });

    editForm.setFieldsValue({
      id: record.id,
      routeName: record.routeName,
      uri: record.uri,
      order: record.order,
      description: record.description,
      routingMode: record.mode || 'SINGLE',
      serviceId: serviceIdForForm || undefined,
      services: editServices,
      predicates: editPredicates,
      filters: editFilters,
      enabled: record.enabled !== false,
      grayRules: record.grayRules?.rules || [],
    });
    setEditRoutingMode(record.mode || 'SINGLE');
    setEditGrayRulesEnabled(record.grayRules?.enabled || false);
    // Store services list for gray rules dropdown
    setEditServicesList(editServices);

    setEditModalVisible(true);
  }, [editForm]);

  const copyToClipboard = (text: string, label: string) => {
    copy(text);
    message.success(t('message.copied_to_clipboard', { label }));
  };

  const handleDelete = useCallback((record: Route) => {
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
  }, [t, loadRoutes]);

  const handleEnableRoute = useCallback(async (record: Route) => {
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
  }, [t, loadRoutes]);

  const handleDisableRoute = useCallback(async (record: Route) => {
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
  }, [t, loadRoutes]);

  // Combined toggle handler for enable/disable
  const handleToggleRoute = useCallback((record: Route) => {
    if (record.enabled) {
      handleDisableRoute(record);
    } else {
      handleEnableRoute(record);
    }
  }, [handleEnableRoute, handleDisableRoute]);

  const getPredicateTag = (predicate: any) => {
    const name = predicate.name;
    let value = '';
    if (name === 'Path') value = predicate.args?.pattern || '/**';
    else if (name === 'Host') value = predicate.args?.pattern || '';
    else if (name === 'Method') value = Array.isArray(predicate.args?.methods) ? predicate.args.methods.join(', ') : '';
    else if (name === 'Header') value = predicate.args?.name || '';
    else value = JSON.stringify(predicate.args);

    const desc = t(`predicate.${name}.detail`, { defaultValue: '' });

    // Premium predicate tag style - soft blue with subtle border
    const predicateTagStyle: React.CSSProperties = {
      borderRadius: '9999px',
      padding: '4px 12px',
      fontSize: '13px',
      fontWeight: 500,
      backgroundColor: 'rgba(59, 130, 246, 0.15)',
      color: '#60a5fa',
      border: '1px solid rgba(59, 130, 246, 0.3)',
      cursor: 'help',
    };

    return (
      <Tooltip key={name} title={desc} placement="top">
        <Tag style={predicateTagStyle} className="predicate-tag">
          <BranchesOutlined /> {name}: <code style={{ fontFamily: 'JetBrains Mono, monospace' }}>{value}</code>
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
        <Divider type="vertical" className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-red-600">{disabledRoutes}</div>
          <div className="stat-label">{t('routes.stats_disabled')}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('routes.title')}</Title>
          <Text type="secondary">{t('routes.page_description_helper')}</Text>
        </div>
        <div className="page-header-right">
          <Input
            placeholder={t('routes.search_placeholder')}
            value={searchTerm}
            onChange={e => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={
              <Button 
                type="text" 
                icon={<SearchOutlined />} 
                onClick={() => {}}
                style={{ color: '#94a3b8' }}
              />
            }
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
                        <div className="route-id-row">
                          <Text type="secondary" className="route-id">{route.id ? route.id.substring(0, 16) : 'N/A'}...</Text>
                          <Tooltip title={t('common.copy')}>
                            <Button
                              type="text"
                              size="small"
                              icon={<CopyOutlined />}
                              className="copy-btn"
                              onClick={(e) => {
                                e.stopPropagation();
                                copy(route.id || '');
                                message.success(t('message.copy_success'));
                              }}
                            />
                          </Tooltip>
                        </div>
                      </div>
                    </div>
                    <div className="route-status">
                      <Badge status={route.enabled ? 'success' : 'default'} text={route.enabled ? t('common.enabled') : t('common.disabled')} />
                    </div>
                    <RouteActionDropdown
                      route={route}
                      onEdit={showRouteEdit}
                      onDetail={showRouteDetail}
                      onToggle={handleToggleRoute}
                      onDelete={handleDelete}
                    />
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
                        route.filters!.map((f, idx) => {
                          // Premium plugin tag style - soft green with subtle border
                          const pluginTagStyle: React.CSSProperties = {
                            borderRadius: '9999px',
                            padding: '4px 12px',
                            fontSize: '13px',
                            fontWeight: 500,
                            backgroundColor: 'rgba(16, 185, 129, 0.15)',
                            color: '#34d399',
                            border: '1px solid rgba(16, 185, 129, 0.3)',
                            cursor: 'help',
                          };

                          return (
                            <Tooltip key={idx} title={t(`plugin.${f.name}.detail`, { defaultValue: '' })} placement="top">
                              <Tag style={pluginTagStyle} className="plugin-tag">
                                <ThunderboltOutlined /> {t(`plugin.${f.name}.name`, { defaultValue: f.name })}
                              </Tag>
                            </Tooltip>
                          );
                        })
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
        onCancel={() => { 
          setCreateModalVisible(false); 
          createForm.resetFields(); 
          createForm.setFieldsValue({ filters: [] });
        }}
        afterOpenChange={(open) => {
          if (open) {
            createForm.setFieldsValue({ filters: [] });
          }
        }}
        footer={null}
        width={720}
        className="route-modal route-create-modal"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate} initialValues={{ order: 0, enabled: true, routingMode: 'SINGLE' }}>
          {/* Basic Info Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <FileTextOutlined className="section-icon" />
              <span className="section-title">{t('routes.basic_info')}</span>
            </div>
            <div className="section-content">
              <Form.Item name="id" label={t('routes.route_name')} rules={[{ required: true }]}>
                <Input placeholder={t('routes.route_name_placeholder')} size="large" />
              </Form.Item>
              <div className="form-row">
                <Form.Item name="order" label={t('routes.order')} className="form-item-half">
                  <Input type="number" placeholder="0" size="large" />
                </Form.Item>
                <Form.Item name="enabled" label={t('routes.enabled')} valuePropName="checked" className="form-item-half" style={{ paddingTop: '24px' }}>
                  <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                </Form.Item>
              </div>
              <Form.Item name="description" label={t('routes.description_label')}>
                <TextArea rows={2} placeholder={t('routes.description_placeholder')} showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Target Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('routes.target_configuration')}</span>
            </div>
            <div className="section-content">
              {/* Routing Mode Selection */}
              <Form.Item name="routingMode" label={t('routes.routing_mode_label')} rules={[{ required: true }]}>
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
                <>
                  <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]}>
                    <Select placeholder={t('routes.select_service')} size="large" onChange={(value, option) => {
                      // value is in format: "static://serviceId" or "lb://serviceName"
                      createForm.setFieldValue('uri', value || '');
                      // For lb:// services, extract namespace/group from option key and store in hidden fields
                      if (value && value.startsWith('lb://')) {
                        const serviceName = value.replace('lb://', '');
                        // Find the matching nacos service to get namespace/group
                        const nacosService = nacosServices.find(s => s.serviceName === serviceName);
                        if (nacosService) {
                          createForm.setFieldValue('serviceNamespace', nacosService.namespace || '');
                          createForm.setFieldValue('serviceGroup', nacosService.group || 'DEFAULT_GROUP');
                        }
                      } else {
                        // Clear namespace/group for static services
                        createForm.setFieldValue('serviceNamespace', undefined);
                        createForm.setFieldValue('serviceGroup', undefined);
                      }
                    }} showSearch>
                      <Select.OptGroup label={<><CloudOutlined style={{ marginRight: 6 }} /> {t('routes.static_services')}</>}>
                        {services.map(s => (
                          <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                            <span>{s.name || s.serviceId}</span>
                            <span className="service-option-tag static">Static</span>
                          </Select.Option>
                        ))}
                      </Select.OptGroup>
                      <Select.OptGroup label={<><GlobalOutlined style={{ marginRight: 6 }} /> {t('routes.nacos_services')}</>}>
                        {nacosServices.map(s => (
                          <Select.Option key={`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`} value={`lb://${s.serviceName}`}>
                            <span>{s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`}</span>
                            <span className="service-option-tag nacos">Nacos</span>
                          </Select.Option>
                        ))}
                      </Select.OptGroup>
                    </Select>
                  </Form.Item>
                  {/* Hidden fields for namespace/group (only for lb:// services) */}
                  <Form.Item name="serviceNamespace" hidden>
                    <Input type="hidden" />
                  </Form.Item>
                  <Form.Item name="serviceGroup" hidden>
                    <Input type="hidden" />
                  </Form.Item>
                </>
              )}

              {/* Multi-Service Mode */}
              {routingMode === 'MULTI' && (
                <>
                  {/* Real-time weight total indicator */}
                  <Form.Item shouldUpdate noStyle>
                    {({ getFieldValue }) => {
                      const serviceList = getFieldValue('services') || [];
                      const totalWeight = serviceList.reduce((sum: number, s: any) => sum + (s?.weight || 0), 0);
                      const isValid = totalWeight === 100;
                      return (
                        <div style={{
                          marginBottom: '12px',
                          padding: '8px 12px',
                          background: isValid ? '#1a3a1a' : '#2a2520',
                          border: `1px solid ${isValid ? '#3d8b3d' : '#8b5a2b'}`,
                          borderRadius: '4px',
                          display: 'flex',
                          alignItems: 'center',
                          gap: '8px'
                        }}>
                          {isValid ? (
                            <Tag color="success">{t('routes.weight_total')}: {totalWeight}% ✓</Tag>
                          ) : (
                            <Tag color="warning">{t('routes.weight_total')}: {totalWeight}% (需为100%)</Tag>
                          )}
                        </div>
                      );
                    }}
                  </Form.Item>
                  <Form.List name="services" initialValue={[{ weight: 100, enabled: true }]}>
                    {(fields, { add, remove }) => (
                      <>
                        {fields.map(({ key, name, ...restField }) => (
                          <div key={key} style={{
                            padding: '8px 10px',
                            marginBottom: '6px',
                            background: '#1a2338',
                            borderRadius: '6px',
                            border: '1px solid rgba(148, 163, 184, 0.15)'
                          }}>
                            <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }}>
                              <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                                <Select
                                  placeholder={t('routes.select_service')}
                                  style={{ width: 220, flexShrink: 0 }}
                                  size="middle"
                                  showSearch
                                  onChange={(value) => {
                                    // Auto-fill serviceName and namespace/group based on selection
                                    let serviceName = '';
                                    let serviceNamespace = undefined;
                                    let serviceGroup = undefined;
                                    if (value?.startsWith('static://')) {
                                      const id = value.replace('static://', '');
                                      const found = services.find(s => s.serviceId === id);
                                      serviceName = found?.name || id;
                                    } else if (value?.startsWith('lb://')) {
                                      serviceName = value.replace('lb://', '');
                                      // Find matching nacos service for namespace/group
                                      const nacosService = nacosServices.find(s => s.serviceName === serviceName);
                                      if (nacosService) {
                                        serviceNamespace = nacosService.namespace || '';
                                        serviceGroup = nacosService.group || 'DEFAULT_GROUP';
                                      }
                                    }
                                    // Update serviceName and namespace/group in the same service binding
                                    const currentServices = createForm.getFieldValue('services') || [];
                                    currentServices[name] = { 
                                      ...currentServices[name], 
                                      serviceName,
                                      serviceNamespace,
                                      serviceGroup
                                    };
                                    createForm.setFieldsValue({ services: currentServices });
                                  }}
                                >
                                  <Select.OptGroup label={<><CloudOutlined style={{ marginRight: 6 }} /> {t('routes.static_services')}</>}>
                                    {services.map(s => (
                                      <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                                        <span>{s.name || s.serviceId}</span>
                                        <span className="service-option-tag static">Static</span>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                  <Select.OptGroup label={<><GlobalOutlined style={{ marginRight: 6 }} /> {t('routes.nacos_services')}</>}>
                                    {nacosServices.map(s => (
                                      <Select.Option key={`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`} value={`lb://${s.serviceName}`}>
                                        <span>{s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`}</span>
                                        <span className="service-option-tag nacos">Nacos</span>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                </Select>
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                                <InputNumber
                                  min={1} max={100}
                                  placeholder="Weight"
                                  size="middle"
                                  style={{ width: 90, flexShrink: 0 }}
                                  addonAfter="%"
                                />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked" initialValue={true}>
                                <Switch size="small" checkedChildren="ON" unCheckedChildren="OFF" />
                              </Form.Item>
                              {fields.length > 1 && (
                                <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} style={{ flexShrink: 0, padding: '4px' }} />
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
                  </div>

                  {grayRulesEnabled && (
                    <Form.List name="grayRules">
                      {(fields, { add, remove }) => (
                        <>
                          {fields.map(({ key, name, ...restField }) => {
                            const ruleType = createForm.getFieldValue(['grayRules', name, 'type']) || 'HEADER';
                            const configuredServices = createFormServices || [];
                            return (
                              <div key={key} style={{
                                padding: '8px 10px',
                                marginBottom: '6px',
                                background: '#2a2520',
                                borderRadius: '6px',
                                border: '1px solid #8b5a2b'
                              }}>
                                <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'nowrap' }}>
                                  <Form.Item {...restField} name={[name, 'type']} noStyle initialValue="HEADER">
                                    <Select
                                      style={{
                                        width: 85,
                                        flexShrink: 0
                                      }}
                                      placeholder={t('routes.rule_type')}
                                      size="small"
                                      suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
                                    >
                                      <Select.Option value="HEADER">Header</Select.Option>
                                      <Select.Option value="COOKIE">Cookie</Select.Option>
                                      <Select.Option value="QUERY">Query</Select.Option>
                                      <Select.Option value="WEIGHT">Weight%</Select.Option>
                                    </Select>
                                  </Form.Item>
                                  {ruleType !== 'WEIGHT' && (
                                    <Form.Item {...restField} name={[name, 'name']} noStyle>
                                      <Input
                                        placeholder={t('routes.gray_rule_name')}
                                        style={{ width: 90, flexShrink: 0 }}
                                        size="small"
                                      />
                                    </Form.Item>
                                  )}
                                  <Form.Item {...restField} name={[name, 'value']} noStyle>
                                    {ruleType === 'WEIGHT' ? (
                                      <InputNumber
                                        min={1}
                                        max={100}
                                        placeholder="%"
                                        style={{ width: 70, flexShrink: 0 }}
                                        addonAfter="%"
                                        size="small"
                                      />
                                    ) : (
                                      <Input
                                        placeholder={t('routes.gray_rule_value')}
                                        style={{ width: 90, flexShrink: 0 }}
                                        size="small"
                                      />
                                    )}
                                  </Form.Item>
                                  <Tooltip title={t('routes.target_service_tooltip')}>
                                    <Form.Item {...restField} name={[name, 'targetVersion']} noStyle>
                                      <Select
                                        style={{ width: 150, flexShrink: 0 }}
                                        placeholder={t('routes.target_service')}
                                        allowClear
                                        showSearch
                                        size="small"
                                        suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
                                        popupClassName="gray-rule-select-dropdown"
                                      >
                                        {configuredServices.map((s: any, idx: number) => {
                                          const serviceId = s?.serviceId?.replace?.('static://', '').replace?.('lb://', '') || s?.serviceId;
                                          const displayName = s?.serviceName || serviceId;
                                          return (
                                            <Select.Option key={idx} value={serviceId}>
                                              {displayName}
                                            </Select.Option>
                                          );
                                        })}
                                      </Select>
                                    </Form.Item>
                                  </Tooltip>
                                  {fields.length > 1 && (
                                    <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                                  )}
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
                <Form.Item name="uri" label={t('routes.target_uri')}>
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
            </div>
            <div className="section-content">
              <Form.List name="filters" initialValue={[]}>
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
        destroyOnClose
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
              <Form.Item name="routeName" label={t('routes.route_name')}>
                <Input disabled size="large" />
              </Form.Item>
              <div className="form-row">
                <Form.Item name="order" label={t('routes.order')} className="form-item-half">
                  <Input type="number" placeholder="0" size="large" />
                </Form.Item>
                <Form.Item name="enabled" label={t('routes.enabled')} valuePropName="checked" className="form-item-half" style={{ paddingTop: '24px' }}>
                  <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                </Form.Item>
              </div>
              <Form.Item name="description" label={t('routes.description_label')}>
                <TextArea rows={2} placeholder={t('routes.description_placeholder')} showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Target Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('routes.target_configuration')}</span>
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
                <>
                  <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]}>
                    <Select placeholder={t('routes.select_service')} size="large" onChange={(value, option) => {
                      editForm.setFieldValue('uri', value || '');
                      // For lb:// services, extract namespace/group and store in hidden fields
                      if (value && value.startsWith('lb://')) {
                        const serviceName = value.replace('lb://', '');
                        const nacosService = nacosServices.find(s => s.serviceName === serviceName);
                        if (nacosService) {
                          editForm.setFieldValue('serviceNamespace', nacosService.namespace || '');
                          editForm.setFieldValue('serviceGroup', nacosService.group || 'DEFAULT_GROUP');
                        }
                      } else {
                        editForm.setFieldValue('serviceNamespace', undefined);
                        editForm.setFieldValue('serviceGroup', undefined);
                      }
                    }} showSearch>
                      <Select.OptGroup label={<><CloudOutlined style={{ marginRight: 6 }} /> {t('routes.static_services')}</>}>
                        {services.map(s => (
                          <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                            <span>{s.name || s.serviceId}</span>
                            <span className="service-option-tag static">Static</span>
                          </Select.Option>
                        ))}
                      </Select.OptGroup>
                      <Select.OptGroup label={<><GlobalOutlined style={{ marginRight: 6 }} /> {t('routes.nacos_services')}</>}>
                        {nacosServices.map(s => (
                          <Select.Option key={`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`} value={`lb://${s.serviceName}`}>
                            <span>{s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`}</span>
                            <span className="service-option-tag nacos">Nacos</span>
                          </Select.Option>
                        ))}
                      </Select.OptGroup>
                    </Select>
                  </Form.Item>
                  {/* Hidden fields for namespace/group (only for lb:// services) */}
                  <Form.Item name="serviceNamespace" hidden>
                    <Input type="hidden" />
                  </Form.Item>
                  <Form.Item name="serviceGroup" hidden>
                    <Input type="hidden" />
                  </Form.Item>
                </>
              )}

              {/* Multi-Service Mode */}
              {editRoutingMode === 'MULTI' && (
                <>
                  {/* Real-time weight total indicator */}
                  <Form.Item shouldUpdate noStyle>
                    {({ getFieldValue }) => {
                      const serviceList = getFieldValue('services') || [];
                      const totalWeight = serviceList.reduce((sum: number, s: any) => sum + (s?.weight || 0), 0);
                      const isValid = totalWeight === 100;
                      return (
                        <div style={{
                          marginBottom: '12px',
                          padding: '8px 12px',
                          background: isValid ? '#1a3a1a' : '#2a2520',
                          border: `1px solid ${isValid ? '#3d8b3d' : '#8b5a2b'}`,
                          borderRadius: '4px',
                          display: 'flex',
                          alignItems: 'center',
                          gap: '8px'
                        }}>
                          {isValid ? (
                            <Tag color="success">{t('routes.weight_total')}: {totalWeight}% ✓</Tag>
                          ) : (
                            <Tag color="warning">{t('routes.weight_total')}: {totalWeight}% (需为100%)</Tag>
                          )}
                        </div>
                      );
                    }}
                  </Form.Item>
                  <Form.List name="services">
                    {(fields, { add, remove }) => (
                      <>
                        {fields.map(({ key, name, ...restField }) => (
                          <div key={key} style={{
                            padding: '8px 10px',
                            marginBottom: '6px',
                            background: '#1a2338',
                            borderRadius: '6px',
                            border: '1px solid rgba(148, 163, 184, 0.15)'
                          }}>
                            <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }}>
                              <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                                <Select
                                  placeholder={t('routes.select_service')}
                                  style={{ width: 220, flexShrink: 0 }}
                                  size="middle"
                                  showSearch
                                  onChange={(value) => {
                                    // Auto-fill serviceName and namespace/group based on selection
                                    let serviceName = '';
                                    let serviceNamespace = undefined;
                                    let serviceGroup = undefined;
                                    if (value?.startsWith('static://')) {
                                      const id = value.replace('static://', '');
                                      const found = services.find(s => s.serviceId === id);
                                      serviceName = found?.name || id;
                                    } else if (value?.startsWith('lb://')) {
                                      serviceName = value.replace('lb://', '');
                                      // Find matching nacos service for namespace/group
                                      const nacosService = nacosServices.find(s => s.serviceName === serviceName);
                                      if (nacosService) {
                                        serviceNamespace = nacosService.namespace || '';
                                        serviceGroup = nacosService.group || 'DEFAULT_GROUP';
                                      }
                                    }
                                    // Update serviceName and namespace/group in the same service binding
                                    const currentServices = editForm.getFieldValue('services') || [];
                                    currentServices[name] = { 
                                      ...currentServices[name], 
                                      serviceName,
                                      serviceNamespace,
                                      serviceGroup
                                    };
                                    editForm.setFieldsValue({ services: currentServices });
                                  }}
                                >
                                  <Select.OptGroup label={<><CloudOutlined style={{ marginRight: 6 }} /> {t('routes.static_services')}</>}>
                                    {services.map(s => (
                                      <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
                                        <span>{s.name || s.serviceId}</span>
                                        <span className="service-option-tag static">Static</span>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                  <Select.OptGroup label={<><GlobalOutlined style={{ marginRight: 6 }} /> {t('routes.nacos_services')}</>}>
                                    {nacosServices.map(s => (
                                      <Select.Option key={`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`} value={`lb://${s.serviceName}`}>
                                        <span>{s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`}</span>
                                        <span className="service-option-tag nacos">Nacos</span>
                                      </Select.Option>
                                    ))}
                                  </Select.OptGroup>
                                </Select>
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                                <InputNumber min={1} max={100} placeholder="Weight" style={{ width: 100, flexShrink: 0 }} addonAfter="%" />
                              </Form.Item>
                              <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked" initialValue={true}>
                                <Switch size="small" checkedChildren="ON" unCheckedChildren="OFF" />
                              </Form.Item>
                              {fields.length > 1 && (
                                <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} style={{ flexShrink: 0 }} />
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
                  </div>

                  {editGrayRulesEnabled && (
                    <Form.List name="grayRules">
                      {(fields, { add, remove }) => (
                        <>
                          {fields.map(({ key, name, ...restField }) => {
                            const ruleType = editForm.getFieldValue(['grayRules', name, 'type']) || 'HEADER';
                            const configuredServices = editServicesList || [];
                            return (
                              <div key={key} style={{
                                padding: '12px',
                                marginBottom: '8px',
                                background: '#2a2520',
                                borderRadius: '6px',
                                border: '1px solid #8b5a2b'
                              }}>
                                <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }}>
                                  <Form.Item {...restField} name={[name, 'type']} noStyle initialValue="HEADER">
                                    <Select 
                                      style={{ 
                                        width: 90, 
                                        flexShrink: 0,
                                        backgroundColor: 'rgba(0, 0, 0, 0.3)',
                                        border: '1px solid rgba(148, 163, 184, 0.15)'
                                      }} 
                                      placeholder={t('routes.rule_type')}
                                      size="small"
                                      suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
                                    >
                                      <Select.Option value="HEADER">Header</Select.Option>
                                      <Select.Option value="COOKIE">Cookie</Select.Option>
                                      <Select.Option value="QUERY">Query</Select.Option>
                                      <Select.Option value="WEIGHT">Weight%</Select.Option>
                                    </Select>
                                  </Form.Item>
                                  {ruleType !== 'WEIGHT' && (
                                    <Form.Item {...restField} name={[name, 'name']} noStyle>
                                      <Input 
                                        placeholder={t('routes.gray_rule_name')} 
                                        style={{ 
                                          width: 100, 
                                          flexShrink: 0,
                                          backgroundColor: 'rgba(0, 0, 0, 0.3)',
                                          border: '1px solid rgba(148, 163, 184, 0.15)',
                                          color: '#e2e8f0'
                                        }} 
                                        size="small"
                                      />
                                    </Form.Item>
                                  )}
                                  <Form.Item {...restField} name={[name, 'value']} noStyle>
                                    {ruleType === 'WEIGHT' ? (
                                      <InputNumber
                                        min={1}
                                        max={100}
                                        placeholder="%"
                                        style={{ width: 70, flexShrink: 0 }}
                                        addonAfter="%"
                                        size="small"
                                      />
                                    ) : (
                                      <Input
                                        placeholder={t('routes.gray_rule_value')}
                                        style={{ width: 90, flexShrink: 0 }}
                                        size="small"
                                      />
                                    )}
                                  </Form.Item>
                                  <Tooltip title={t('routes.target_service_tooltip')}>
                                    <Form.Item {...restField} name={[name, 'targetVersion']} noStyle>
                                      <Select
                                        style={{ width: 150, flexShrink: 0 }}
                                        placeholder={t('routes.target_service')}
                                        allowClear
                                        showSearch
                                        size="small"
                                        suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
                                        popupClassName="gray-rule-select-dropdown"
                                      >
                                        {configuredServices.map((s: any, idx: number) => {
                                          const serviceId = s?.serviceId?.replace?.('static://', '').replace?.('lb://', '') || s?.serviceId;
                                          const displayName = s?.serviceName || serviceId;
                                          return (
                                            <Select.Option key={idx} value={serviceId}>
                                              {displayName}
                                            </Select.Option>
                                          );
                                        })}
                                      </Select>
                                    </Form.Item>
                                  </Tooltip>
                                  {fields.length > 1 && (
                                    <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} />
                                  )}
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
                <Form.Item name="uri" label={t('routes.target_uri')}>
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


      {/* Route Detail Drawer */}
      <Drawer
        placement="right"
        width={520}
        open={detailDrawerVisible}
        closable={false}
        onClose={() => { setDetailDrawerVisible(false); setSelectedRoute(null); }}
        className="route-detail-drawer"
        mask={true}
        maskClosable={true}
        destroyOnClose
        style={{ zIndex: 999 }}
      >
        {selectedRoute && (
          <div className="drawer-content">
            <div className="drawer-header">
              <div className="drawer-header-left">
                <div className="drawer-icon route-icon"><ApiOutlined /></div>
                <div className="drawer-title-wrapper">
                  <Title level={4} className="drawer-title">{selectedRoute.routeName || 'Route Detail'}</Title>
                  <Text type="secondary" copyable={{ text: selectedRoute.id }}>{selectedRoute.id?.substring(0, 20)}...</Text>
                </div>
              </div>
              <Button type="text" icon={<CloseOutlined />} onClick={() => { setDetailDrawerVisible(false); setSelectedRoute(null); }} className="drawer-close-btn" />
            </div>
            <div className="drawer-status-bar">
              <Badge status={selectedRoute.enabled ? 'success' : 'default'} text={selectedRoute.enabled ? t('common.enabled') : t('common.disabled')} />
              <Tag color="blue">Order: {selectedRoute.order ?? 0}</Tag>
              <Tag color={selectedRoute.mode === 'MULTI' ? 'purple' : 'cyan'}>
                {selectedRoute.mode === 'MULTI' ? t('routes.multi_service') : t('routes.single_service')}
              </Tag>
            </div>
            {selectedRoute.mode === 'SINGLE' && (
              <div className="drawer-section">
                <div className="section-title"><CloudOutlined /> {t('routes.target_uri')}</div>
                <div className="uri-display"><Text code copyable>{selectedRoute.uri || 'N/A'}</Text></div>
              </div>
            )}
            {selectedRoute.mode === 'SINGLE' && selectedRoute.serviceId && (
              <div className="drawer-section">
                <div className="section-title"><CloudOutlined /> {t('routes.target_service')}</div>
                <Text code>{selectedRoute.serviceId}</Text>
              </div>
            )}
            {selectedRoute.mode === 'MULTI' && selectedRoute.services && selectedRoute.services.length > 0 && (
              <div className="drawer-section">
                <div className="section-title"><SplitCellsOutlined /> {t('routes.services')} <span className="count">{selectedRoute.services.length}</span></div>
                <div className="services-detail-list">
                  {selectedRoute.services.map((s, idx) => (
                    <div key={idx} className={`service-detail-card ${s.enabled ? 'healthy' : 'unhealthy'}`}>
                      <div className="service-main-info">
                        <Text strong>{s.serviceName || s.serviceId}</Text>
                        <Tag color={s.enabled ? 'green' : 'default'}>{s.enabled ? t('common.enabled') : t('common.disabled')}</Tag>
                      </div>
                      <div className="service-meta">
                        <span>{t('routes.weight')}: {s.weight}%</span>
                        {s.type && <Tag color={s.type === 'DISCOVERY' ? 'blue' : 'orange'} style={{ marginLeft: 8 }}>{s.type}</Tag>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {selectedRoute.grayRules?.enabled && selectedRoute.grayRules?.rules && selectedRoute.grayRules.rules.length > 0 && (
              <div className="drawer-section">
                <div className="section-title"><BranchesOutlined /> {t('routes.gray_rules')} <span className="count">{selectedRoute.grayRules.rules.length}</span></div>
                <div className="gray-rules-detail-list">
                  {selectedRoute.grayRules.rules.map((rule, idx) => {
                    // Find service name from targetVersion (serviceId)
                    const targetService = selectedRoute.services?.find((s: any) => {
                      const sId = s?.serviceId?.replace?.('static://', '').replace?.('lb://', '') || s?.serviceId;
                      return sId === rule.targetVersion;
                    });
                    const displayTarget = targetService?.serviceName || rule.targetVersion;
                    return (
                      <div key={idx} className="gray-rule-detail-card">
                        <Tag color="gold">{rule.type}</Tag>
                        {rule.type !== 'WEIGHT' && <Text code>{rule.name}={rule.value}</Text>}
                        {rule.type === 'WEIGHT' && <Text>{rule.value}%</Text>}
                        <Text type="secondary">→ {displayTarget}</Text>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
            {selectedRoute.description && (
              <div className="drawer-section">
                <div className="section-title"><FileTextOutlined /> {t('routes.description')}</div>
                <Text type="secondary">{selectedRoute.description}</Text>
              </div>
            )}
            {selectedRoute.predicates && selectedRoute.predicates.length > 0 && (
              <div className="drawer-section">
                <div className="section-title"><BranchesOutlined /> {t('routes.predicates')} <span className="count">{selectedRoute.predicates.length}</span></div>
                <div className="predicates-detail-list">
                  {selectedRoute.predicates.map((p, idx) => (
                    <div key={idx} className="predicate-detail-card">
                      <Tag color="purple">{p.name}</Tag>
                      <pre className="detail-json">{JSON.stringify(p.args, null, 2)}</pre>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {selectedRoute.filters && selectedRoute.filters.length > 0 && (
              <div className="drawer-section">
                <div className="section-title"><ThunderboltOutlined /> {t('routes.plugins')} <span className="count">{selectedRoute.filters.length}</span></div>
                <div className="plugins-detail-list">
                  {selectedRoute.filters.map((f, idx) => (
                    <div key={idx} className="plugin-detail-card">
                      <Tag color="blue">{t(`plugin.${f.name}.name`, { defaultValue: f.name })}</Tag>
                      <pre className="detail-json">{JSON.stringify(f.args, null, 2)}</pre>
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div className="drawer-actions">
              <Button type="primary" icon={<EditOutlined />} onClick={() => showRouteEdit(selectedRoute)}>{t('common.edit')}</Button>
              <Button danger icon={<DeleteOutlined />} onClick={() => {
                Modal.confirm({ title: t('common.confirm'), content: t('message.confirm_delete_route', { name: selectedRoute.routeName || selectedRoute.id }), okText: t('common.delete'), okType: 'danger', cancelText: t('common.cancel'), onOk: () => handleDelete(selectedRoute) });
              }}>{t('common.delete')}</Button>
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default RoutesPage;
