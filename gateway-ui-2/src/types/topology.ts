/**
 * Traffic topology domain types.
 * Aligned with controller/TrafficTopologyController.java (/api/topology)
 * and service/TrafficTopologyService (TopologyGraph / TopologyNode /
 * TopologyEdge / TrafficMetrics / TrafficSummary toMap output).
 * All endpoints use the standard ApiResponse envelope.
 */

/** Service instance entry inside a service node (backend Map). */
export interface TopologyServiceInstance {
  ip: string;
  port: number;
  weight?: number;
  enabled?: boolean;
  static?: boolean;
}

/** Metrics the backend attaches to nodes/edges (requestCount etc.). */
export interface TopologyMetricsRecord {
  requestCount?: number;
  avgLatency?: number;
  errorRate?: number;
  [key: string]: unknown;
}

/** TopologyNode.toMap() — node types: gateway | route | service | client. */
export interface TopologyNode {
  id: string;
  type: string;
  name: string;
  status?: string;
  /** route enabled flag (route nodes only) */
  enabled?: boolean;
  instanceId?: string;
  routeId?: string;
  serviceId?: string;
  /** 静态服务 / 服务发现 */
  serviceType?: string;
  clientIp?: string;
  uri?: string;
  instances?: TopologyServiceInstance[];
  totalInstances?: number;
  healthyInstances?: number;
  metrics?: TopologyMetricsRecord;
}

/** TopologyEdge.toMap() — types: client-gateway | gateway-route | route-service. */
export interface TopologyEdge {
  source: string;
  target: string;
  type: string;
  routeId?: string;
  metrics?: TopologyMetricsRecord;
}

/** TrafficMetrics.toMap() — graph-level aggregates. */
export interface TopologyTrafficMetrics {
  totalRequests: number;
  serverErrors: number;
  totalErrors: number;
  avgLatency: number;
  uniqueClients: number;
  /** routes with traffic */
  uniqueRoutes: number;
  /** total configured routes */
  configuredRoutes: number;
  /** enabled route count */
  enabledRoutes: number;
  errorRate: number;
  requestsPerSecond: number;
}

/** GET /api/topology/{instanceId} — TopologyGraph.toMap(). */
export interface TopologyGraph {
  instanceId: string;
  generatedAt: number;
  timeRangeMinutes: number;
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  metrics?: TopologyTrafficMetrics;
}

/** GET /api/topology/{instanceId}/summary — TrafficSummary.toMap(). */
export interface TrafficSummary {
  totalRequests: number;
  serverErrors: number;
  clientErrors: number;
  avgLatency: number;
  requestsPerSecond: number;
  timeRangeMinutes: number;
}
