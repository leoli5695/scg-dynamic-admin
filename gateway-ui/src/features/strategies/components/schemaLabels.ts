/**
 * Localization of backend-authored config schema text.
 *
 * The strategy type schema is authored in Chinese (static SUB_SCHEMAS in
 * StrategyTypeService plus the strategy_types.config_schema seed JSON); the backend
 * adds an `…En` variant per key. These helpers pick the variant for the active
 * language, falling back to Chinese when no English variant exists.
 */
import i18n from '../../../i18n';
import type { StrategyTypeField, StrategyTypeSubSchema } from '../../../types/strategy';

const prefersZh = () => (i18n.resolvedLanguage || i18n.language || 'zh').startsWith('zh');
const localized = (zh?: string, en?: string) => (prefersZh() ? zh || en : en || zh);

export const fieldLabel = (field: StrategyTypeField) => localized(field.label, field.labelEn) || field.name;
export const optionLabel = (opt: { label: string; labelEn?: string }) => localized(opt.label, opt.labelEn) || opt.label;
export const fieldPlaceholder = (field: StrategyTypeField) => localized(field.placeholder, field.placeholderEn);
export const fieldUnit = (field: StrategyTypeField) => localized(field.unit, field.unitEn);
export const sectionText = (sub: StrategyTypeSubSchema) => localized(sub.sectionLabel, sub.sectionLabelEn);
export const sectionDesc = (sub: StrategyTypeSubSchema) => localized(sub.sectionDescription, sub.sectionDescriptionEn);
export const ruleText = (sub: StrategyTypeSubSchema) => localized(sub.ruleLabel, sub.ruleLabelEn);
export const dimensionText = (schema: { dimensionLabel?: string; dimensionLabelEn?: string }) =>
  localized(schema.dimensionLabel, schema.dimensionLabelEn);