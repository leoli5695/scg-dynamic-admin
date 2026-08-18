/**
 * RoutesPage 组件拆分后的子组件和类型定义
 */

// ============================================================================
// Type Definitions
// ============================================================================

export type RoutingMode = 'SINGLE' | 'MULTI';
export type ServiceBindingType = 'STATIC' | 'DISCOVERY';

/** Predicate definition for route matching */
export interface RoutePredicate {
  name: string;
  args?: Record<string, unknown> | string;
}

/** Filter (plugin) definition for route processing */
export interface RouteFilter {
  name: string;
  args?: Record<string, unknown> | string;
}

/** Service instance for load balancing */
export interface ServiceInstance {
  serviceId: string;
  serviceName?: string;
  weight?: number;
  enabled?: boolean;
  version?: string;
  type?: ServiceBindingType;
  serviceNamespace?: string;
  serviceGroup?: string;
}

export interface RouteServiceBinding {
  serviceId: string;
  serviceName: string;
  weight: number;
  version?: string;
  enabled: boolean;
  description?: string;
  type?: ServiceBindingType;
  serviceNamespace?: string;
  serviceGroup?: string;
}

export interface GrayRule {
  type: 'HEADER' | 'COOKIE' | 'QUERY' | 'WEIGHT';
  name?: string;
  value: string;
  targetVersion: string;
  description?: string;
}

export interface GrayRules {
  enabled: boolean;
  rules: GrayRule[];
}

export interface Route {
  id: string;
  routeName: string;
  uri: string;
  mode?: RoutingMode;
  serviceId?: string;
  serviceNamespace?: string;
  serviceGroup?: string;
  services?: RouteServiceBinding[];
  grayRules?: GrayRules;
  predicates?: RoutePredicate[];
  filters?: RouteFilter[];
  order?: number;
  enabled?: boolean;
  description?: string;
  metadata?: Record<string, unknown>;
}

export interface Service {
  name: string;
  serviceId: string;
  loadBalancer: string;
  instances?: ServiceInstance[];
}

export interface NacosService {
  serviceName: string;
  namespace?: string;
  group?: string;
  instanceCount?: number;
  displayName?: string;
}

/** Form values for route create/edit */
export interface RouteFormValues {
  id?: string;
  routeName?: string;
  order?: number;
  enabled?: boolean;
  description?: string;
  predicates?: RoutePredicate[];
  filters?: RouteFilter[];
  serviceId?: string;
  serviceNamespace?: string;
  serviceGroup?: string;
  uri?: string;
  services?: RouteServiceBinding[];
  grayRules?: GrayRule[];
}

export interface RoutesPageProps {
  instanceId?: string;
}

// ============================================================================
// Sub-Component Props Interfaces
// ============================================================================

export interface RouteStatsBarProps {
  total: number;
  enabled: number;
  disabled: number;
}

export interface RouteSearchBarProps {
  searchTerm: string;
  onSearchChange: (value: string) => void;
  statusFilter: string;
  onStatusFilterChange: (value: string) => void;
  onCreateClick: () => void;
}

export interface RouteCardProps {
  route: Route;
  onEdit: (route: Route) => void;
  onDetail: (route: Route) => void;
  onToggle: (route: Route) => void;
  onDelete: (route: Route) => void;
}

export interface RouteDetailDrawerProps {
  visible: boolean;
  route: Route | null;
  onClose: () => void;
  onEdit: (route: Route) => void;
  onDelete: (route: Route) => void;
}
