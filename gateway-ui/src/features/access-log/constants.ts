/**
 * Access log feature constants: default sensitive fields (kept client-side,
 * backend only persists what the frontend sends) and the Fluent Bit
 * collection guide templates shown in the configuration tab.
 */

/** Fallback sent when no sensitive fields are configured yet. */
export const DEFAULT_SENSITIVE_FIELDS = [
  'password', 'token', 'authorization', 'secret',
  'apiKey', 'api_key', 'accessKey', 'access_key',
  'clientSecret', 'client_secret', 'cookie',
];

/** Preset history time ranges used by both K8S and DB history queries. */
export const HISTORY_RANGE_OPTIONS = ['1h', '6h', '12h', '24h', '7d', '30d'] as const;
export type HistoryRange = (typeof HISTORY_RANGE_OPTIONS)[number];

/** Convert a preset range key to seconds. */
export const rangeToSeconds = (range: HistoryRange): number => {
  switch (range) {
    case '1h': return 3600;
    case '6h': return 6 * 3600;
    case '12h': return 12 * 3600;
    case '24h': return 24 * 3600;
    case '7d': return 7 * 24 * 3600;
    case '30d': return 30 * 24 * 3600;
    default: return 3600;
  }
};

export const FLUENT_BIT_K8S_CONFIG = `# Fluent Bit DaemonSet 配置示例
[SERVICE]
    Flush         5
    Log_Level     info
    Parsers_File  parsers.conf

[INPUT]
    Name              tail
    Path              /var/log/containers/*gateway*.log
    Parser            json
    Tag               gateway.access
    Mem_Buf_Limit     50MB

[FILTER]
    Name              kubernetes
    Match             gateway.*
    Kube_URL          https://kubernetes.default.svc:443
    Kube_Tag_Prefix   gateway.access.

[OUTPUT]
    Name              http
    Match             gateway.*
    Host              gateway-admin-service
    Port              8080
    URI               /api/access-log/collect
    Format            json`;

export const FLUENT_BIT_K8S_CONFIGMAP = `apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: gateway
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush         5
        Log_Level     info
        Parsers_File  parsers.conf

    [INPUT]
        Name              tail
        Path              /var/log/containers/*gateway*.log
        Parser            json
        Tag               gateway.access
        Mem_Buf_Limit     50MB

    [FILTER]
        Name              kubernetes
        Match             gateway.*
        Kube_URL          https://kubernetes.default.svc:443
        Kube_Tag_Prefix   gateway.access.

    [OUTPUT]
        Name              http
        Match             gateway.*
        Host              gateway-admin-service
        Port              8080
        URI               /api/access-log/collect
        Format            json

  parsers.conf: |
    [PARSER]
        Name   json
        Format json`;

export const FLUENT_BIT_DOCKER_CONFIG = `# Docker Compose sidecar 示例
services:
  gateway:
    volumes:
      - access-logs:/app/logs/access

  fluent-bit:
    image: fluent/fluent-bit:latest
    volumes:
      - access-logs:/app/logs/access
    config:
      [INPUT]
        Name    tail
        Path    /app/logs/access/access-*.log
        Parser  json

      [OUTPUT]
        Name    http
        Host    gateway-admin
        Port    8080`;

export const FLUENT_BIT_LOCAL_CONFIG = `# Fluent Bit 本地采集配置
[INPUT]
    Name    tail
    Path    ./logs/access/access-*.log
    Parser  json
    Tag     gateway.access

[OUTPUT]
    Name    http
    Host    localhost
    Port    8080
    URI     /api/access-log/collect`;

export const FLUENT_BIT_CUSTOM_CONFIG = `# Fluent Bit 自定义路径配置
[INPUT]
    Name    tail
    Path    /your/custom/path/access-*.log
    Parser  json

[OUTPUT]
    Name    http
    Host    gateway-admin
    Port    8080`;
