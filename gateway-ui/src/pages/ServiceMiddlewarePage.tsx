import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Table, Tag, Space, Button, Spin, message, Typography, Tooltip,
  Empty, Descriptions, Progress, Badge, Row, Col, Statistic, Modal,
  Tabs, Alert, Drawer, Input
} from 'antd';
import {
  DatabaseOutlined, ReloadOutlined, CloudServerOutlined,
  ApiOutlined, CheckCircleOutlined, ExclamationCircleOutlined,
  CloseCircleOutlined, EyeOutlined, LineChartOutlined,
  ThunderboltOutlined, DashboardOutlined, SearchOutlined,
  InfoCircleOutlined, LinkOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import './ServiceMiddlewarePage.css';

const { Text, Title } = Typography;

// Prometheus metric name friendly display mapping
// Maps raw metric names to user-friendly Chinese names
// Keys can be exact matches or prefixes for partial matching
const METRIC_NAME_MAP: Record<string, string> = {
  // Elasticsearch metrics
  'elasticsearch_cluster_health_status': 'ES集群健康状态',
  'elasticsearch_cluster_health_number_of_nodes': 'ES集群节点数',
  'elasticsearch_cluster_health_number_of_data_nodes': 'ES数据节点数',
  'elasticsearch_indices_indexing_index_total': 'ES索引写入总数',
  'elasticsearch_indices_docs': 'ES文档数',
  'elasticsearch_indices_store_size_bytes': 'ES存储大小',
  'elasticsearch_indices_search_query_time_seconds': 'ES查询耗时(秒)',
  'elasticsearch_indices_search_query_time_seconds_count': 'ES查询次数',
  'elasticsearch_indices_search_query_total': 'ES查询总数',
  'elasticsearch_indices_get_time_seconds': 'ES获取耗时',
  'elasticsearch_indices_fetch_time_seconds': 'ES Fetch耗时',
  'elasticsearch_jvm_memory_used_bytes': 'ES JVM内存使用',
  'elasticsearch_jvm_memory_max_bytes': 'ES JVM最大内存',
  'elasticsearch_thread_pool_active_count': 'ES活跃线程数',
  'elasticsearch_thread_pool_queue_count': 'ES线程队列',
  'elasticsearch_process_cpu_percent': 'ES CPU使用率',
  'elasticsearch_os_cpu_percent': 'ES系统CPU',
  'elasticsearch_fs_total_size_bytes': 'ES磁盘总大小',
  'elasticsearch_transport_rx_size_bytes_total': 'ES接收流量',
  'elasticsearch_transport_tx_size_bytes_total': 'ES发送流量',

  // Redis metrics
  'redis_connected_clients': 'Redis连接客户端数',
  'redis_memory_used_bytes': 'Redis内存使用',
  'redis_memory_max_bytes': 'Redis最大内存',
  'redis_keyspace_keys': 'Redis键数量',
  'redis_commands_processed_total': 'Redis命令处理总数',
  'redis_hit_rate': 'Redis命中率',
  'redis_connected_slaves': 'Redis从节点数',
  'redis_blocked_clients': 'Redis阻塞客户端数',
  'redis_db_keys': 'Redis数据库键数',
  'redis_instantaneous_ops_per_sec': 'Redis瞬时操作速率',
  'redis_total_net_input_bytes': 'Redis网络输入',
  'redis_total_net_output_bytes': 'Redis网络输出',
  'redis_expired_keys_total': 'Redis过期键总数',
  'redis_evicted_keys_total': 'Redis驱逐键总数',

  // MySQL metrics
  'mysql_global_status_threads_connected': 'MySQL连接线程数',
  'mysql_global_status_threads_running': 'MySQL运行线程数',
  'mysql_global_status_queries': 'MySQL查询总数',
  'mysql_global_status_questions': 'MySQL请求总数',
  'mysql_global_status_bytes_received': 'MySQL接收字节',
  'mysql_global_status_bytes_sent': 'MySQL发送字节',
  'mysql_global_status_buffer_pool_size': 'MySQL缓冲池大小',
  'mysql_global_status_innodb_buffer_pool_reads': 'MySQL缓冲池读取',
  'mysql_global_status_connections': 'MySQL连接总数',
  'mysql_global_status_aborted_connections': 'MySQL中断连接数',
  'mysql_global_status_slow_queries': 'MySQL慢查询数',
  'mysql_global_status_table_locks_waited': 'MySQL表锁等待',
  'mysql_global_status_innodb_row_lock_waits': 'MySQL行锁等待',
  'mysql_global_status_innodb_log_writes': 'MySQL日志写入',
  'mysql_global_status_innodb_data_reads': 'MySQL数据读取',
  'mysql_global_status_innodb_data_writes': 'MySQL数据写入',

  // MongoDB metrics
  'mongodb_connections': 'MongoDB连接数',
  'mongodb_connections_current': 'MongoDB当前连接数',
  'mongodb_connections_available': 'MongoDB可用连接数',
  'mongodb_document_operations_total': 'MongoDB文档操作总数',
  'mongodb_memory_used_bytes': 'MongoDB内存使用',
  'mongodb_oplog_size_bytes': 'MongoDB Oplog大小',
  'mongodb_network_bytes_total': 'MongoDB网络流量',
  'mongodb_query_operations_total': 'MongoDB查询总数',
  'mongodb_insert_operations_total': 'MongoDB插入总数',
  'mongodb_update_operations_total': 'MongoDB更新总数',
  'mongodb_delete_operations_total': 'MongoDB删除总数',

  // RabbitMQ metrics
  'rabbitmq_connections_total': 'RabbitMQ连接总数',
  'rabbitmq_channels_total': 'RabbitMQ通道总数',
  'rabbitmq_queues_total': 'RabbitMQ队列总数',
  'rabbitmq_messages_ready': 'RabbitMQ待处理消息',
  'rabbitmq_messages_unacked': 'RabbitMQ未确认消息',
  'rabbitmq_messages_total': 'RabbitMQ消息总数',
  'rabbitmq_consumers_total': 'RabbitMQ消费者总数',
  'rabbitmq_exchange_messages_published_total': 'RabbitMQ发布消息总数',
  'rabbitmq_queue_messages_published_total': 'RabbitMQ队列发布消息',
  'rabbitmq_queue_messages_delivered_total': 'RabbitMQ队列投递消息',

  // RocketMQ metrics
  'rocketmq_broker_total_messages': 'RocketMQ消息总数',
  'rocketmq_broker_messages_in_today': 'RocketMQ今日入库消息',
  'rocketmq_broker_messages_out_today': 'RocketMQ今日出库消息',
  'rocketmq_broker_topic_count': 'RocketMQ主题数',
  'rocketmq_broker_group_count': 'RocketMQ消费组数',
  'rocketmq_broker_tps': 'RocketMQ TPS',
  'rocketmq_consumer_group_diff': 'RocketMQ消费延迟',
  'rocketmq_consumer_group_message_accumulated': 'RocketMQ消息堆积',

  // Kafka metrics
  'kafka_broker_messages_per_sec': 'Kafka消息速率',
  'kafka_broker_bytes_in_per_sec': 'Kafka入库速率',
  'kafka_broker_bytes_out_per_sec': 'Kafka出库速率',
  'kafka_partition_count': 'Kafka分区数',
  'kafka_consumer_lag': 'Kafka消费延迟',
  'kafka_topic_messages_total': 'Kafka主题消息总数',
  'kafka_consumer_messages_consumed_total': 'Kafka消费消息数',
  'kafka_producer_messages_produced_total': 'Kafka生产消息数',

  // JVM/Generic metrics
  'jvm_memory_used_bytes': 'JVM内存使用',
  'jvm_memory_max_bytes': 'JVM最大内存',
  'jvm_gc_collection_seconds_count': 'JVM GC次数',
  'jvm_gc_collection_seconds_sum': 'JVM GC耗时',
  'jvm_threads_current': 'JVM当前线程数',
  'jvm_threads_daemon': 'JVM守护线程数',
  'jvm_classes_loaded': 'JVM加载类数',
  'jvm_classes_unloaded_total': 'JVM卸载类数',
  'process_cpu_seconds_total': '进程CPU时间',
  'process_open_fds': '进程打开文件数',
  'process_resident_memory_bytes': '进程内存',
  'process_virtual_memory_bytes': '进程虚拟内存',

  // System metrics
  'node_cpu_seconds_total': 'CPU使用时间',
  'node_memory_used_bytes': '内存使用',
  'node_memory_available_bytes': '可用内存',
  'node_disk_read_bytes_total': '磁盘读取',
  'node_disk_written_bytes_total': '磁盘写入',
  'node_network_receive_bytes_total': '网络接收',
  'node_network_transmit_bytes_total': '网络发送',
  'node_load1': '系统负载(1分钟)',
  'node_load5': '系统负载(5分钟)',
  'node_load15': '系统负载(15分钟)',
  'node_filesystem_size_bytes': '文件系统大小',
  'node_filesystem_avail_bytes': '文件系统可用',
};

/**
 * Get friendly display name for a Prometheus metric
 * Returns mapped name if exists, otherwise returns original name
 */
const getMetricFriendlyName = (metricName: string): string => {
  // Step 1: Extract core metric name (remove label suffixes)
  // e.g., "elasticsearch_indices_search_query_time_seconds_elasticsearch_search" 
  //       -> "elasticsearch_indices_search_query_time_seconds"
  const parts = metricName.split('_');
  let coreMetricName = metricName;

  // Find where label suffixes start (common patterns)
  // Labels usually appear after the metric name, like "_elasticsearch_search", "_redis", "_true_true_true"
  const labelPatterns = ['elasticsearch', 'redis', 'mysql', 'mongodb', 'rabbitmq', 'kafka', 'rocketmq',
                          'true', 'false', 'search', 'index', 'primary', 'secondary', 'node', 'cluster'];
  let labelStartIndex = -1;

  for (let i = parts.length - 1; i >= 4; i--) {
    const part = parts[i];
    if (labelPatterns.includes(part) || part.match(/^\d+\.\d+\.\d+/) || part.match(/^10\.|^172\.|^192\./)) {
      labelStartIndex = i;
    } else {
      break;
    }
  }

  if (labelStartIndex > 0) {
    coreMetricName = parts.slice(0, labelStartIndex).join('_');
  }

  // Step 2: Exact match first (for both original and core name)
  if (METRIC_NAME_MAP[metricName]) {
    return METRIC_NAME_MAP[metricName];
  }
  if (METRIC_NAME_MAP[coreMetricName]) {
    return METRIC_NAME_MAP[coreMetricName];
  }

  // Step 3: Prefix match - check if metric name starts with any key
  for (const [key, friendlyName] of Object.entries(METRIC_NAME_MAP)) {
    if (coreMetricName.startsWith(key) || metricName.startsWith(key)) {
      return friendlyName;
    }
  }

  // Step 4: Contains match - check if key is contained in metric name
  for (const [key, friendlyName] of Object.entries(METRIC_NAME_MAP)) {
    if (coreMetricName.includes(key) || metricName.includes(key)) {
      return friendlyName;
    }
  }

  // Step 5: No match - return cleaned up core metric name
  return coreMetricName;
};

// Middleware type definitions
interface MiddlewareInfo {
  type: string;
  host: string;
  port: number;
  exporterUrl: string;
  status: string;
  version?: string;
  lastReportTime?: string;
}

interface ServiceMiddleware {
  serviceName: string;
  middlewares: MiddlewareInfo[];
  middlewareCount?: number;
  reportTime: string;
  status: string;
  hint?: string;
}

interface MiddlewareMetric {
  name: string;
  value: number;
  unit: string;
  status: 'healthy' | 'warning' | 'critical';
}

interface ServiceMiddlewarePageProps {
  instanceId?: string;
}

const ServiceMiddlewarePage: React.FC<ServiceMiddlewarePageProps> = ({ instanceId }) => {
  const [services, setServices] = useState<ServiceMiddleware[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedService, setSelectedService] = useState<ServiceMiddleware | null>(null);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [metricsLoading, setMetricsLoading] = useState(false);
  const [serviceMetrics, setServiceMetrics] = useState<Record<string, MiddlewareMetric[]>>({});
  const [searchTerm, setSearchTerm] = useState('');
  const { t } = useTranslation();
  const navigate = useNavigate();

  // Load services middleware data
  const loadServicesMiddleware = useCallback(async () => {
    try {
      setLoading(true);
      const params = instanceId ? { instanceId } : {};
      // Call the backend API to get all services with their middleware info
      const res = await api.get('/api/service-middleware/services', { params });
      if (res.data.code === 200) {
        setServices(res.data.data || []);
      }
    } catch (e: any) {
      message.error(t('message.load_middleware_failed', { error: e.message }) || `加载中间件信息失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [t, instanceId]);

  useEffect(() => {
    loadServicesMiddleware();
  }, [loadServicesMiddleware]);

  // Load metrics for a specific service
  const loadServiceMetrics = useCallback(async (serviceName: string) => {
    try {
      setMetricsLoading(true);
      const res = await api.get(`/api/service-middleware/${serviceName}/metrics`);
      if (res.data.code === 200) {
        setServiceMetrics(prev => ({
          ...prev,
          [serviceName]: res.data.data || []
        }));
      }
    } catch (e: any) {
      console.error('Failed to load metrics:', e);
    } finally {
      setMetricsLoading(false);
    }
  }, []);

  // Open service detail drawer
  const openServiceDetail = useCallback(async (service: ServiceMiddleware) => {
    setSelectedService(service);
    setDetailDrawerVisible(true);
    // Load metrics for this service
    await loadServiceMetrics(service.serviceName);
  }, [loadServiceMetrics]);

  // Close drawer
  const handleCloseDrawer = useCallback(() => {
    setDetailDrawerVisible(false);
    setSelectedService(null);
  }, []);

  // Filter services by search term
  const filteredServices = useMemo(() => {
    return services.filter(s =>
      s.serviceName.toLowerCase().includes(searchTerm.toLowerCase())
    );
  }, [services, searchTerm]);

  // Get middleware type icon and color
  const getMiddlewareTypeInfo = useCallback((type: string) => {
    const info: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
      redis: { color: 'red', icon: <DatabaseOutlined />, label: 'Redis' },
      mysql: { color: 'blue', icon: <DatabaseOutlined />, label: 'MySQL' },
      rocketmq: { color: 'orange', icon: <ThunderboltOutlined />, label: 'RocketMQ' },
      elasticsearch: { color: 'green', icon: <SearchOutlined />, label: 'Elasticsearch' },
      kafka: { color: 'purple', icon: <ThunderboltOutlined />, label: 'Kafka' },
      mongodb: { color: 'cyan', icon: <DatabaseOutlined />, label: 'MongoDB' },
    };
    return info[type.toLowerCase()] || { color: 'default', icon: <DatabaseOutlined />, label: type };
  }, []);

  // Get status color
  const getStatusColor = useCallback((status: string) => {
    switch (status?.toLowerCase()) {
      case 'healthy':
      case 'up':
      case 'running':
        return 'success';
      case 'warning':
      case 'degraded':
        return 'warning';
      case 'critical':
      case 'down':
      case 'error':
        return 'error';
      default:
        return 'default';
    }
  }, []);

  // Get status icon
  const getStatusIcon = useCallback((status: string) => {
    switch (status?.toLowerCase()) {
      case 'healthy':
      case 'up':
      case 'running':
        return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
      case 'warning':
      case 'degraded':
        return <ExclamationCircleOutlined style={{ color: '#faad14' }} />;
      case 'critical':
      case 'down':
      case 'error':
        return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      default:
        return <InfoCircleOutlined style={{ color: '#8c8c8c' }} />;
    }
  }, []);

  // Calculate overall health for a service
  const getServiceHealth = useCallback((service: ServiceMiddleware) => {
    if (!service.middlewares || service.middlewares.length === 0) {
      return { score: 0, status: 'no_data' };
    }
    const healthyCount = service.middlewares.filter(m =>
      m.status?.toLowerCase() === 'healthy' || m.status?.toLowerCase() === 'up'
    ).length;
    const totalCount = service.middlewares.length;
    const score = Math.round((healthyCount / totalCount) * 100);
    return {
      score,
      status: score >= 90 ? 'healthy' : score >= 60 ? 'warning' : 'critical'
    };
  }, []);

  // Table columns
  const columns = [
    {
      title: t('middleware.service_name') || '服务名称',
      dataIndex: 'serviceName',
      key: 'serviceName',
      width: 200,
      render: (name: string) => (
        <Space>
          <CloudServerOutlined style={{ color: '#1890ff' }} />
          <Text strong>{name}</Text>
        </Space>
      ),
    },
    {
      title: t('middleware.middlewares') || '中间件',
      dataIndex: 'middlewares',
      key: 'middlewares',
      render: (middlewares: MiddlewareInfo[]) => (
        <Space size="small">
          {middlewares?.map((mw, idx) => {
            const typeInfo = getMiddlewareTypeInfo(mw.type);
            return (
              <Tooltip key={idx} title={`${typeInfo.label}: ${mw.host}:${mw.port}`}>
                <Tag color={typeInfo.color} icon={typeInfo.icon}>
                  {typeInfo.label}
                </Tag>
              </Tooltip>
            );
          })}
          {(!middlewares || middlewares.length === 0) && (
            <Text type="secondary">-</Text>
          )}
        </Space>
      ),
    },
    {
      title: t('middleware.health') || '健康状态',
      key: 'health',
      width: 120,
      render: (_: any, record: ServiceMiddleware) => {
        const health = getServiceHealth(record);
        const color = health.status === 'healthy' ? '#52c41a' :
                      health.status === 'warning' ? '#faad14' : '#ff4d4f';
        return (
          <Space>
            {getStatusIcon(health.status)}
            <Progress
              percent={health.score}
              size="small"
              style={{ width: 60 }}
              strokeColor={color}
              showInfo={false}
            />
            <Text style={{ fontSize: 12 }}>{health.score}%</Text>
          </Space>
        );
      },
    },
    {
      title: t('middleware.report_time') || '上报时间',
      dataIndex: 'reportTime',
      key: 'reportTime',
      width: 180,
      render: (time: string) => time ? new Date(time).toLocaleString() : '-',
    },
    {
      title: t('common.actions') || '操作',
      key: 'actions',
      width: 100,
      render: (_: any, record: ServiceMiddleware) => (
        <Space>
          <Tooltip title={t('common.detail') || '详情'}>
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => openServiceDetail(record)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  // Stats calculation
  const totalServices = services.length;
  const healthyServices = useMemo(() =>
    services.filter(s => getServiceHealth(s).status === 'healthy').length,
    [services, getServiceHealth]
  );
  const totalMiddlewares = useMemo(() =>
    services.reduce((sum, s) => sum + (s.middlewares?.length || 0), 0),
    [services]
  );

  return (
    <div className="service-middleware-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{totalServices}</div>
          <div className="stat-label">{t('middleware.stats_services') || '服务总数'}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{healthyServices}</div>
          <div className="stat-label">{t('middleware.stats_healthy') || '健康服务'}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-blue-600">{totalMiddlewares}</div>
          <div className="stat-label">{t('middleware.stats_middlewares') || '中间件总数'}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">
            {t('middleware.title') || '服务中间件管理'}
          </Title>
          <Text type="secondary">
            {t('middleware.description') || '查看各服务所使用的中间件信息及健康状态'}
          </Text>
        </div>
        <div className="page-header-right">
          <Input
            placeholder={t('middleware.search_placeholder') || '搜索服务名称'}
            value={searchTerm}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={<SearchOutlined style={{ color: '#94a3b8' }} />}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={loadServicesMiddleware}
            loading={loading}
          >
            {t('common.refresh') || '刷新'}
          </Button>
        </div>
      </div>

      {/* Alert for no data */}
      {services.length === 0 && !loading && (
        <Alert
          type="info"
          showIcon
          message={t('middleware.no_data_hint') || '暂无中间件数据'}
          description={t('middleware.no_data_description') || '请确保下游服务已集成 gateway-trace-starter 并正确配置上报地址'}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Services Table */}
      <Card className="middleware-table-card">
        <Spin spinning={loading}>
          <Table
            dataSource={filteredServices}
            columns={columns}
            rowKey="serviceName"
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              showQuickJumper: true,
            }}
            locale={{
              emptyText: (
                <Empty
                  image={<CloudServerOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />}
                  description={t('middleware.empty_description') || '暂无服务中间件数据'}
                />
              ),
            }}
          />
        </Spin>
      </Card>

      {/* Service Detail Drawer */}
      <Drawer
        placement="right"
        styles={{ wrapper: { width: 600 } }}
        open={detailDrawerVisible}
        closable={false}
        onClose={handleCloseDrawer}
        className="middleware-detail-drawer"
        destroyOnClose
      >
        {selectedService && (
          <div className="drawer-content">
            {/* Drawer Header */}
            <div className="drawer-header">
              <div className="drawer-header-left">
                <div className="drawer-icon"><CloudServerOutlined /></div>
                <div className="drawer-title-wrapper">
                  <Title level={4} className="drawer-title">
                    {selectedService.serviceName}
                  </Title>
                  <Text type="secondary">
                    {t('middleware.middleware_count') || '中间件数量'}: {selectedService.middlewares?.length || 0}
                  </Text>
                </div>
              </div>
              <Button
                type="text"
                icon={<CloseCircleOutlined />}
                onClick={handleCloseDrawer}
                className="drawer-close-btn"
              />
            </div>

            {/* Health Overview */}
            <div className="drawer-health-section">
              <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.health_score') || '健康评分'}
                      value={getServiceHealth(selectedService).score}
                      suffix="%"
                      valueStyle={{
                        color: getServiceHealth(selectedService).status === 'healthy' ? '#52c41a' :
                               getServiceHealth(selectedService).status === 'warning' ? '#faad14' : '#ff4d4f'
                      }}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.healthy_count') || '健康数量'}
                      value={selectedService.middlewares?.filter(m =>
                        m.status?.toLowerCase() === 'healthy' || m.status?.toLowerCase() === 'up'
                      ).length || 0}
                      suffix={`/ ${selectedService.middlewares?.length || 0}`}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.last_report') || '最后上报'}
                      value={selectedService.reportTime ?
                        new Date(selectedService.reportTime).toLocaleTimeString() : '-'
                      }
                    />
                  </Col>
                </Row>
              </Card>
            </div>

            {/* Middleware List */}
            <div className="drawer-section">
              <div className="section-title">
                <DatabaseOutlined /> {t('middleware.middleware_list') || '中间件列表'}
              </div>
              {selectedService.middlewares?.length > 0 ? (
                <div className="middleware-list">
                  {selectedService.middlewares.map((mw, idx) => {
                    const typeInfo = getMiddlewareTypeInfo(mw.type);
                    return (
                      <Card
                        key={idx}
                        size="small"
                        className={`middleware-card ${getStatusColor(mw.status)}`}
                        style={{ marginBottom: 12 }}
                      >
                        <Descriptions column={2} size="small">
                          <Descriptions.Item label={t('middleware.type') || '类型'}>
                            <Tag color={typeInfo.color} icon={typeInfo.icon}>
                              {typeInfo.label}
                            </Tag>
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.status') || '状态'}>
                            <Badge status={getStatusColor(mw.status)} text={mw.status || 'Unknown'} />
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.address') || '地址'}>
                            <Text copyable>{mw.host}:{mw.port}</Text>
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.version') || '版本'}>
                            {mw.version || '-'}
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.monitor_url') || '监控地址'} span={2}>
                            <Space>
                              <Text copyable style={{ maxWidth: 300 }} ellipsis>
                                {mw.exporterUrl || '-'}
                              </Text>
                              {mw.exporterUrl && (
                                <Tooltip title={t('middleware.view_metrics') || '查看详细指标'}>
                                  <Button
                                    type="link"
                                    size="small"
                                    icon={<LineChartOutlined />}
                                    onClick={() => {
                                      // 新窗口打开 MiddlewareDetailPage
                                      const url = `/middleware-detail?serviceName=${encodeURIComponent(selectedService.serviceName)}&middlewareType=${encodeURIComponent(mw.type)}&exporterUrl=${encodeURIComponent(mw.exporterUrl)}`;
                                      window.open(url, '_blank');
                                    }}
                                  />
                                </Tooltip>
                              )}
                            </Space>
                          </Descriptions.Item>
                        </Descriptions>
                      </Card>
                    );
                  })}
                </div>
              ) : (
                <Empty description={t('middleware.no_middlewares') || '暂无中间件信息'} />
              )}
            </div>

            {/* Integration Guide - Show different message based on status */}
            <div className="drawer-section">
              {selectedService.status === 'no_middleware' ? (
                <Alert
                  type="warning"
                  showIcon
                  icon={<ExclamationCircleOutlined />}
                  message={t('middleware.hint_no_middleware') || '服务尚未上报中间件信息'}
                  description={
                    <span>
                      {t('middleware.no_data_description') || '请确保下游服务已集成 gateway-trace-starter 并正确配置上报地址'}
                      <a
                        href="https://github.com/your-org/gateway-trace-starter"
                        target="_blank"
                        rel="noopener noreferrer"
                        style={{ marginLeft: 8 }}
                      >
                        <LinkOutlined /> {t('middleware.learn_more') || '了解更多'}
                      </a>
                    </span>
                  }
                />
              ) : (
                <Alert
                  type="info"
                  showIcon
                  icon={<InfoCircleOutlined />}
                  message={t('middleware.integration_hint') || '集成提示'}
                  description={
                    <span>
                      {t('middleware.integration_description') || '此服务已集成 gateway-trace-starter，自动上报中间件信息。'}
                      <a
                        href="https://github.com/your-org/gateway-trace-starter"
                        target="_blank"
                        rel="noopener noreferrer"
                        style={{ marginLeft: 8 }}
                      >
                        <LinkOutlined /> {t('middleware.learn_more') || '了解更多'}
                      </a>
                    </span>
                  }
                />
              )}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default ServiceMiddlewarePage;