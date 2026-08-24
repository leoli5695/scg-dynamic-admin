/**
 * Strategy domain types, aligned with gateway-admin:
 * - model/StrategyDefinition.java (Nacos-stored strategy payload)
 * - model/StrategyTypeEntity.java + StrategyTypeService.toDto
 *   (strategy type metadata with dynamic config schema)
 * - my-gateway model/TokenQuotaConfig.java (TOKEN_RATE_LIMITER config shape)
 */

/** model/StrategyDefinition scope constants. */
export type StrategyScope = 'GLOBAL' | 'ROUTE';

/**
 * Strategy-specific configuration. Free-form by design:
 * each strategy type defines its own schema (see StrategyTypeSchema).
 */
export type StrategyConfig = Record<string, unknown>;

/** model/StrategyDefinition — both request body and list item. */
export interface StrategyDefinition {
  strategyId: string;
  strategyName: string;
  /** typeCode referencing the strategy_types table. */
  strategyType: string;
  scope: StrategyScope;
  /** Required when scope is ROUTE. */
  routeId?: string;
  /** Higher = evaluated first. Backend default: 100. */
  priority: number;
  enabled: boolean;
  config: StrategyConfig;
  /** UI/DB only — transient on the backend, never pushed to Nacos. */
  description?: string;
}

/** One renderable field inside a strategy type config schema. */
export interface StrategyTypeField {
  name: string;
  /** Field widget type: number | text | password | textarea | select | switch | object | array. */
  type: string;
  label?: string;
  labelEn?: string;
  default?: unknown;
  min?: number;
  max?: number;
  unit?: string;
  placeholder?: string;
  required?: boolean;
  options?: Array<{ value: string; label: string }>;
  colSpan?: number;
}

/** Sub-schema for dynamic sections (AUTH types, transform sections, mock modes...). */
export interface StrategyTypeSubSchema {
  fields?: StrategyTypeField[];
  sectionLabel?: string;
  sectionDescription?: string;
  /** Multi-rule pattern (fieldMapping, dataMasking, tenantQuotas...). */
  multiRule?: boolean;
  ruleLabel?: string;
  ruleKeyField?: string;
  ruleFields?: StrategyTypeField[];
}

/** Dynamic form schema stored in strategy_types.config_schema (JSON). */
export interface StrategyTypeSchema {
  fields?: StrategyTypeField[];
  subSchemas?: Record<string, StrategyTypeSubSchema>;
  hasSubSchemas?: boolean;
  /** MULTI_DIM_RATE_LIMITER specifics. */
  multiDimension?: boolean;
  dimensionOptions?: Array<{ value: string; label: string }>;
  dimensionLabel?: string;
}

/**
 * Strategy type metadata DTO — StrategyTypeService.toDto output.
 * Note: configSchema is enriched with AUTH/TOKEN_RATE_LIMITER subSchemas
 * by getFullConfigSchema.
 */
export interface StrategyTypeInfo {
  typeCode: string;
  typeName: string;
  typeNameEn: string;
  /** antd icon component name, e.g. "ThunderboltOutlined". */
  icon: string;
  color: string;
  /** traffic | security | resilience | transform | observability | misc. */
  category: string;
  description?: string;
  configSchema?: StrategyTypeSchema;
  filterClass?: string;
  enabled: boolean;
  sortOrder: number;
}
