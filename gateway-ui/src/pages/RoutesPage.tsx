import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Card, Button, Space, message, Spin, Tag, Modal, Form, Input, Switch,
  Select, Empty, Radio, Tooltip, Badge, Divider, Typography, Pagination,
  InputNumber, Drawer, Alert
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, CopyOutlined,
  EditOutlined, CompassOutlined,
  ApiOutlined, BranchesOutlined, ThunderboltOutlined, FileTextOutlined,
  CloudOutlined, GlobalOutlined, SplitCellsOutlined, CloseOutlined,
  SearchOutlined, LinkOutlined, RocketOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import logger from '../utils/logger';
import { useTranslation } from 'react-i18next';
import { useSearchParams, useNavigate } from 'react-router-dom';
import copy from 'copy-to-clipboard';
import type { RoutingMode, ServiceBindingType, Route, Service, NacosService, RoutePredicate, RouteFilter, GrayRule, RouteFormValues, RouteServiceBinding } from './RoutesPage/types';
import type { AxiosError } from 'axios';
import { parsePredicateArgs, parseFilterArgs, stringifyPredicateArgs, stringifyFilterArgs } from './RoutesPage/utils';
import RouteActionDropdown from './RoutesPage/RouteActionDropdown';
import PredicateItem from './RoutesPage/PredicateItem';
import FilterItem from './RoutesPage/FilterItem';
import RouteStatsBar from './RoutesPage/RouteStatsBar';
import RouteSearchBar from './RoutesPage/RouteSearchBar';
import RouteCard from './RoutesPage/RouteCard';
import RouteDetailDrawer from './RoutesPage/RouteDetailDrawer';
import ServiceBindingForm from './RoutesPage/ServiceBindingForm';
import GrayRulesForm from './RoutesPage/GrayRulesForm';
import './RoutesPage.premium.css';

const { Text, Title } = Typography;
const { TextArea } = Input;

const RoutesPage: React.FC<{ instanceId?: string }> = ({ instanceId }) => {
  const [routes, setRoutes] = useState<Route[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [nacosServices, setNacosServices] = useState<NacosService[]>([]);
  // Use ref to store nacosServices for stable callback references
  const nacosServicesRef = useRef<NacosService[]>(nacosServices);
  // Use ref to store services for stable callback references
  const servicesRef = useRef<Service[]>(services);
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
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  // Watch services list for gray rules dropdown (reactive updates) - for create form only
  const createFormServices = Form.useWatch('services', createForm);

  useEffect(() => {
    loadRoutes();
    loadServices();
    loadNacosServices();
  }, [instanceId]);

  // 处理从拓扑图跳转过来的 highlight 参数，自动打开对应路由的详情
  useEffect(() => {
    const highlightId = searchParams.get('highlight');
    if (highlightId && routes.length > 0) {
      const targetRoute = routes.find(r => r.id === highlightId || r.routeName === highlightId);
      if (targetRoute) {
        setSelectedRoute(targetRoute);
        setDetailDrawerVisible(true);
        // 清除 highlight 参数，避免刷新页面时重复打开
        searchParams.delete('highlight');
        searchParams.delete('from');
        setSearchParams(searchParams, { replace: true });
      }
    }
  }, [routes, searchParams, setSearchParams]);

  // Reset to first page when search/filter changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchTerm, statusFilter]);

  // Keep ref in sync with nacosServices state for stable callback references
  useEffect(() => {
    nacosServicesRef.current = nacosServices;
  }, [nacosServices]);

  // Keep ref in sync with services state for stable callback references
  useEffect(() => {
    servicesRef.current = services;
  }, [services]);

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
    } catch (error) {
      const err = error as AxiosError<{ message?: string }>;
      logger.error('Load routes error:', err);
      message.error(t('message.load_routes_failed', { error: err.response?.data?.message || err.message }));
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
    } catch (error) {
      logger.error('Load services error:', error);
    }
  };

  const loadNacosServices = async () => {
    try {
      const response = await api.get('/api/services/nacos-discovery');
      if (response.data.code === 200) {
        const nacosServiceList: NacosService[] = response.data.data || [];
        setNacosServices(nacosServiceList);
      }
    } catch (error) {
      logger.error('Load Nacos services error:', error);
    }
  };

  // Memoize filtered routes to avoid recalculation on every render
  const filteredRoutes = useMemo(() => routes.filter(route => {
    const matchesSearch = !searchTerm ||
      route.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (route.routeName && route.routeName.toLowerCase().includes(searchTerm.toLowerCase())) ||
      (route.uri && route.uri.toLowerCase().includes(searchTerm.toLowerCase()));

    const matchesStatus = statusFilter === 'all' ||
      (statusFilter === 'enabled' && route.enabled) ||
      (statusFilter === 'disabled' && !route.enabled);

    return matchesSearch && matchesStatus;
  }), [routes, searchTerm, statusFilter]);

  // Memoize route statistics
  const routeStats = useMemo(() => ({
    total: routes.length,
    enabled: routes.filter(r => r.enabled).length,
    disabled: routes.filter(r => !r.enabled).length,
  }), [routes]);

  // Pagination
  const totalRoutes = routeStats.total;
  const enabledRoutes = routeStats.enabled;
  const disabledRoutes = routeStats.disabled;
  const startIndex = (currentPage - 1) * pageSize;
  const endIndex = startIndex + pageSize;
  const paginatedRoutes = useMemo(() => filteredRoutes.slice(startIndex, endIndex), [filteredRoutes, startIndex, endIndex]);

  const handleCreate = (values: RouteFormValues) => {
    if (!values.predicates || values.predicates.length === 0) {
      message.error(t('routes.predicate_required'));
      return;
    }

    const hasPathPredicate = values.predicates.some((p: RoutePredicate) => p.name === 'Path');
    if (!hasPathPredicate) {
      message.error(t('routes.path_predicate_required'));
      return;
    }

    const formattedPredicates = values.predicates.map((p: RoutePredicate) => ({
      name: p.name,
      args: parsePredicateArgs(p.name, p.args)
    }));

    const formattedFilters = (values.filters || []).map((f: RouteFilter) => ({
      name: f.name,
      args: parseFilterArgs(f.name, f.args)
    }));

    // Build route data based on routing mode
    // Note: id (UUID) is generated by backend, routeName is the business name
    const routeData: Record<string, unknown> = {
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
      const totalWeight = serviceBindings.reduce((sum: number, s: RouteServiceBinding) => sum + (s.weight || 0), 0);
      if (totalWeight !== 100) {
        message.error(t('routes.weight_must_be_100', { total: totalWeight }));
        return;
      }

      routeData.services = serviceBindings.map((s: RouteServiceBinding) => {
        // s.serviceId is in format: static://xxx or lb://serviceName
        const uriParts = (s.serviceId || '').split('://');
        const protocol = uriParts[0] || 'static';
        const serviceId = uriParts[1] || s.serviceId;

        // Derive type from protocol: static:// -> STATIC, lb:// -> DISCOVERY
        const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

        // For Nacos services (lb://), use serviceId directly as serviceName
        // For static services, look up name from services list (use ref for reliability)
        // Priority: use s.serviceName if already set by onChange, otherwise lookup
        let serviceName = s.serviceName;
        if (protocol === 'lb') {
          serviceName = serviceId;
        } else if (!serviceName || /^[a-f0-9-]{36}$/i.test(serviceName)) {
          // serviceName is UUID or missing, lookup from servicesRef
          const found = servicesRef.current.find(svc => svc.serviceId === serviceId);
          serviceName = found?.name || s.serviceName || serviceId;
        }

        // For Nacos services, lookup namespace/group from nacosServices list
        let serviceNamespace: string | undefined;
        let serviceGroup: string | undefined;
        if (protocol === 'lb') {
          const nacosService = nacosServices.find(ns => ns.serviceName === serviceId);
          if (nacosService) {
            serviceNamespace = nacosService.namespace || '';
            serviceGroup = nacosService.group || 'DEFAULT_GROUP';
          }
        }

        return {
          serviceId: serviceId,
          serviceName: serviceName,
          weight: s.weight || 100,
          version: s.version,
          enabled: s.enabled !== false,
          type: bindingType,
          serviceNamespace: serviceNamespace,
          serviceGroup: serviceGroup
        };
      });

      // Add gray rules if enabled
      if (grayRulesEnabled && values.grayRules && values.grayRules.length > 0) {
        routeData.grayRules = {
          enabled: true,
          rules: values.grayRules.map((r: GrayRule) => ({
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

  const handleUpdate = (values: RouteFormValues) => {
    if (!values.predicates || values.predicates.length === 0) {
      message.error(t('routes.predicate_required'));
      return;
    }

    const hasPathPredicate = values.predicates.some((p: RoutePredicate) => p.name === 'Path');
    if (!hasPathPredicate) {
      message.error(t('routes.path_predicate_required'));
      return;
    }

    // Validate multi-service weight if in MULTI mode
    if (editRoutingMode === 'MULTI' && values.services && values.services.length > 0) {
      const totalWeight = values.services.reduce((sum: number, s: RouteServiceBinding) => sum + (s.weight || 0), 0);
      if (totalWeight !== 100) {
        message.error(t('routes.weight_must_be_100', { total: totalWeight }));
        return;
      }
    }

    const formattedPredicates = values.predicates.map((p: RoutePredicate) => ({
      name: p.name,
      args: parsePredicateArgs(p.name, p.args)
    }));

    const formattedFilters = (values.filters || []).map((f: RouteFilter) => ({
      name: f.name,
      args: parseFilterArgs(f.name, f.args)
    }));

    // Build route data based on routing mode
    // Note: id (UUID) is from path parameter, routeName is the business name
    const routeData: Record<string, unknown> = {
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

      routeData.services = serviceBindings.map((s: RouteServiceBinding) => {
        const uriParts = (s.serviceId || '').split('://');
        const protocol = uriParts[0] || 'static';
        const serviceId = uriParts[1] || s.serviceId;
        const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

        // For Nacos services (lb://), lookup namespace/group from nacosServices list
        let serviceNamespace: string | undefined;
        let serviceGroup: string | undefined;
        if (protocol === 'lb') {
          const nacosService = nacosServices.find(ns => ns.serviceName === serviceId);
          if (nacosService) {
            serviceNamespace = nacosService.namespace || '';
            serviceGroup = nacosService.group || 'DEFAULT_GROUP';
          }
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
          serviceNamespace: serviceNamespace,
          serviceGroup: serviceGroup
        };
      });

      // Add gray rules if enabled
      if (editGrayRulesEnabled && values.grayRules && values.grayRules.length > 0) {
        routeData.grayRules = {
          enabled: true,
          rules: values.grayRules.map((r: GrayRule) => ({
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

    const editPredicates = (record.predicates || []).map((p: RoutePredicate) => ({
      name: p.name,
      args: stringifyPredicateArgs(p.name, p.args)
    }));

    const editFilters = (record.filters || []).map((f: RouteFilter) => ({
      name: f.name,
      args: stringifyFilterArgs(f.name, f.args)
    }));

    // Process services for multi-service mode - ensure serviceId has protocol prefix
    // Infer type from nacosServices list, NOT from stored type field (which may be wrong)
    // Use ref to avoid dependency on nacosServices state
    const editServices = (record.services || []).map((s: RouteServiceBinding) => {
      // If serviceId already has protocol, extract the pure ID
      let pureServiceId = s.serviceId || '';
      let protocol = 'static';

      if (pureServiceId.includes('://')) {
        const parts = pureServiceId.split('://');
        protocol = parts[0] || 'static';
        pureServiceId = parts[1] || pureServiceId;
      } else {
        // Infer protocol from nacosServices list (more reliable than stored type)
        const isNacosService = nacosServicesRef.current.some(ns => ns.serviceName === pureServiceId);
        protocol = isNacosService ? 'lb' : 'static';
      }

      // Ensure serviceName is correctly set
      // Priority: 1. Use existing serviceName if it's not a UUID format
      //           2. Lookup from services list (servicesRef)
      //           3. Keep original serviceName as fallback
      let serviceName = s.serviceName;
      const isUuidFormat = /^[a-f0-9-]{36}$/i.test(serviceName || '');

      // Only lookup if serviceName is UUID format or missing
      if (isUuidFormat || !serviceName) {
        const found = servicesRef.current.find(svc => svc.serviceId === pureServiceId);
        if (found?.name) {
          serviceName = found.name;
        }
        // If not found in servicesRef, keep original serviceName (don't fallback to UUID)
      }

      return {
        ...s,
        serviceId: `${protocol}://${pureServiceId}`,
        serviceName: serviceName
      };
    });

    editForm.setFieldsValue({
      id: record.id,
      routeName: record.routeName,
      uri: record.uri,
      order: record.order,
      description: record.description,
      routingMode: record.mode || 'SINGLE',
      serviceId: serviceIdForForm || undefined,
      serviceNamespace: record.serviceNamespace || '',
      serviceGroup: record.serviceGroup || 'DEFAULT_GROUP',
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
        } catch (error) {
          const err = error as AxiosError<{ message?: string }>;
          const errorMsg = err.response?.data?.message || err.message;
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
    } catch (error) {
      const err = error as AxiosError<{ message?: string }>;
      const errorMsg = err.response?.data?.message || err.message;
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
    } catch (error) {
      const err = error as AxiosError<{ message?: string }>;
      const errorMsg = err.response?.data?.message || err.message;
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

  return (
    <div className="routes-page">
      {/* Stats Bar */}
      <RouteStatsBar total={totalRoutes} enabled={enabledRoutes} disabled={disabledRoutes} />

      {/* Header */}
      <RouteSearchBar
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        onCreateClick={() => setCreateModalVisible(true)}
      />

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
              {paginatedRoutes.map((route) => (
                <RouteCard
                  key={route.id}
                  route={route}
                  onEdit={showRouteEdit}
                  onDetail={showRouteDetail}
                  onToggle={handleToggleRoute}
                  onDelete={handleDelete}
                />
              ))}
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
              <ServiceBindingForm
                form={createForm}
                routingMode={routingMode}
                onRoutingModeChange={(mode) => {
                  setRoutingMode(mode);
                  createForm.setFieldsValue({ serviceId: undefined, services: [], uri: '' });
                }}
                services={services}
                nacosServices={nacosServices}
                servicesRef={servicesRef}
                configuredServices={createFormServices || []}
                servicesWatchName="services"
              />

              <GrayRulesForm
                form={createForm}
                grayRulesEnabled={grayRulesEnabled}
                onGrayRulesToggle={setGrayRulesEnabled}
                configuredServices={createFormServices || []}
                servicesRef={servicesRef}
              />
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

          {/* Filters/Plugins Section - Now guides to Plugin Binding page */}
          <div className="form-section-modern">
            <div className="section-header">
              <RocketOutlined className="section-icon" />
              <span className="section-title">{t('routes.plugins_section')}</span>
            </div>
            <div className="section-content">
              <Alert
                type="info"
                showIcon
                message={t('routes.plugins_guide_title')}
                description={t('routes.plugins_guide_desc')}
                style={{ marginBottom: 16 }}
              />
              <Button
                type="dashed"
                block
                icon={<LinkOutlined />}
                size="large"
                onClick={() => {
                  navigate(`/instances/${instanceId}/plugin_bindings`);
                }}
              >
                {t('routes.manage_plugin_bindings')}
              </Button>
              <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
                {t('routes.plugins_hint')}
              </Text>
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
          <ServiceBindingForm
            form={editForm}
            routingMode={editRoutingMode}
            onRoutingModeChange={(mode) => {
              setEditRoutingMode(mode);
              editForm.setFieldsValue({ serviceId: undefined, services: [], uri: '' });
            }}
            services={services}
            nacosServices={nacosServices}
            servicesRef={servicesRef}
            configuredServices={editServicesList || []}
            servicesWatchName="services"
          />

          <GrayRulesForm
            form={editForm}
            grayRulesEnabled={editGrayRulesEnabled}
            onGrayRulesToggle={setEditGrayRulesEnabled}
            configuredServices={editServicesList || []}
            servicesRef={servicesRef}
          />

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

          {/* Filters/Plugins Section - Now guides to Plugin Binding page */}
          <div className="form-section-modern">
            <div className="section-header">
              <RocketOutlined className="section-icon" />
              <span className="section-title">{t('routes.plugins_section')}</span>
            </div>
            <div className="section-content">
              <Alert
                type="info"
                showIcon
                message={t('routes.plugins_guide_title')}
                description={t('routes.plugins_guide_desc')}
                style={{ marginBottom: 16 }}
              />
              <Button
                type="dashed"
                block
                icon={<LinkOutlined />}
                size="large"
                onClick={() => {
                  setEditModalVisible(false);
                  navigate(`/instances/${instanceId}/plugin_bindings`);
                }}
              >
                {t('routes.manage_plugin_bindings')}
              </Button>
              <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
                {t('routes.plugins_hint')}
              </Text>
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
      <RouteDetailDrawer
        visible={detailDrawerVisible}
        route={selectedRoute}
        onClose={() => { setDetailDrawerVisible(false); setSelectedRoute(null); }}
        onEdit={showRouteEdit}
        onDelete={handleDelete}
      />
    </div>
  );
};

export default RoutesPage;
