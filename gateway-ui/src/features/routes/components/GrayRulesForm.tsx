/**
 * Gray rules form — shared between create and edit modals.
 * Handles HEADER, COOKIE, QUERY and WEIGHT rule types.
 */
import React from 'react';
import {
  Button, Divider, Form, Input, InputNumber, Select, Switch, Tooltip
} from 'antd';
import type { FormInstance } from 'antd';
import type { DefaultOptionType } from 'antd/es/select';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RouteServiceBinding } from '../../../types/route';
import type { ServiceDefinition } from '../../../types/service';
import { resolveServiceDisplayName } from '../utils';

interface GrayRulesFormProps {
  form: FormInstance;
  grayRulesEnabled: boolean;
  onGrayRulesToggle: (enabled: boolean) => void;
  /** Configured services for the target dropdown. */
  configuredServices: Array<Partial<RouteServiceBinding>>;
  services: ServiceDefinition[];
}

const GrayRulesForm: React.FC<GrayRulesFormProps> = ({
  form,
  grayRulesEnabled,
  onGrayRulesToggle,
  configuredServices,
  services,
}) => {
  const { t } = useTranslation();

  return (
    <>
      <Divider />
      <div style={{ marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '12px' }}>
        <Switch
          checked={grayRulesEnabled}
          onChange={onGrayRulesToggle}
          checkedChildren={t('routes.gray_rules_enabled')}
          unCheckedChildren={t('routes.gray_rules_disabled')}
        />
      </div>

      {grayRulesEnabled && (
        <Form.List name="grayRules">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name, ...restField }) => {
                const ruleType = form.getFieldValue(['grayRules', name, 'type']) || 'HEADER';
                return (
                  <div key={key} style={{
                    padding: '8px 10px',
                    marginBottom: '6px',
                    background: '#2a2520',
                    borderRadius: '6px',
                    border: '1px solid #8b5a2b',
                  }}
                  >
                    <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'nowrap' }}>
                      <Form.Item {...restField} name={[name, 'type']} noStyle initialValue="HEADER">
                        <Select
                          style={{ width: 85, flexShrink: 0 }}
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
                            labelRender={(option: DefaultOptionType) => {
                              const value = option?.value;
                              if (!value) return option?.label || '';
                              const found = resolveServiceDisplayName(String(value), String(value), services);
                              return found || option?.label || value;
                            }}
                          >
                            {configuredServices.map((s, idx) => {
                              const serviceId = s?.serviceId?.replace('static://', '').replace('lb://', '') || s?.serviceId || '';
                              const displayName = resolveServiceDisplayName(s?.serviceName, serviceId, services);
                              return (
                                <Select.Option key={idx} value={serviceId} label={displayName}>
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
  );
};

export default GrayRulesForm;
