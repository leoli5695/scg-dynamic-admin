import React, { useState, useEffect } from 'react';
import { Form, Select, Input, Tooltip, Button } from 'antd';
import type { FormInstance } from 'antd';
import type { TFunction } from 'i18next';

interface FilterItemProps {
  form: FormInstance;
  restField: Record<string, unknown>;
  name: number;
  t: TFunction;
  onRemove: (index: number) => void;
}

/**
 * Filter 表单项组件
 * 支持多种过滤器类型：请求头、响应头、路径修改、状态码、安全等
 */
const FilterItem: React.FC<FilterItemProps> = ({ form, restField, name, t, onRemove }) => {
  const filterName = Form.useWatch(['filters', name, 'name'], form);
  const [filterType, setFilterType] = useState<string>('');

  useEffect(() => {
    if (filterName && filterName !== filterType) {
      setFilterType(filterName);
    }
  }, [filterName]);

  const needsKeyValueInput = [
    'AddRequestHeader', 'SetRequestHeader', 'AddRequestParameter',
    'AddResponseHeader', 'SetResponseHeader'
  ];

  const needsNameOnly = [
    'RemoveRequestHeader', 'RemoveResponseHeader', 'RemoveRequestParameter'
  ];

  const isRewritePath = filterType === 'RewritePath';
  const isRedirectTo = filterType === 'RedirectTo';
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
            onChange={(value) => {
              setFilterType(value);
              form.setFieldValue(['filters', name, 'args'], '');
            }}
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

      <Form.Item {...restField} name={[name, 'args']} noStyle hidden>
        <Input />
      </Form.Item>

      <Form.Item shouldUpdate={(prev, cur) => {
        const prevArgs = prev.filters?.[name]?.args;
        const curArgs = cur.filters?.[name]?.args;
        return prevArgs !== curArgs;
      }} noStyle>
        {({ getFieldValue, setFieldValue }) => {
          const currentArgs = getFieldValue(['filters', name, 'args']) || '';

          if (needsKeyValueInput.includes(filterType)) {
            const colonIndex = currentArgs.indexOf(':');
            const nameValue = colonIndex === -1 ? currentArgs : currentArgs.substring(0, colonIndex);
            const valueValue = colonIndex === -1 ? '' : currentArgs.substring(colonIndex + 1);

            return (
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <Input
                  placeholder="Name"
                  value={nameValue}
                  style={{
                    width: 110,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${e.target.value}:${valueValue}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
                <span style={{ color: '#64748b' }}>:</span>
                <Input
                  placeholder="Value"
                  value={valueValue}
                  style={{
                    width: 110,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${nameValue}:${e.target.value}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
              </div>
            );
          }

          if (isRewritePath) {
            const commaIndex = currentArgs.indexOf(',');
            const regexp = commaIndex === -1 ? currentArgs : currentArgs.substring(0, commaIndex);
            const replacement = commaIndex === -1 ? '' : currentArgs.substring(commaIndex + 1);

            return (
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <Input
                  placeholder="Regexp"
                  value={regexp}
                  style={{
                    width: 130,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${e.target.value},${replacement}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
                <span style={{ color: '#64748b' }}>,</span>
                <Input
                  placeholder="Replacement"
                  value={replacement}
                  style={{
                    width: 130,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${regexp},${e.target.value}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
              </div>
            );
          }

          if (isRedirectTo) {
            const commaIndex = currentArgs.indexOf(',');
            const status = commaIndex === -1 ? currentArgs : currentArgs.substring(0, commaIndex);
            const url = commaIndex === -1 ? '' : currentArgs.substring(commaIndex + 1);

            return (
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <Input
                  placeholder="Status"
                  value={status}
                  style={{
                    width: 80,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${e.target.value},${url}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
                <span style={{ color: '#64748b' }}>,</span>
                <Input
                  placeholder="URL"
                  value={url}
                  style={{
                    width: 160,
                    backgroundColor: 'rgba(0, 0, 0, 0.3)',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                    color: '#e2e8f0'
                  }}
                  onChange={(e) => {
                    const newValue = `${status},${e.target.value}`;
                    setFieldValue(['filters', name, 'args'], newValue);
                  }}
                />
              </div>
            );
          }

          if (noParamsNeeded) {
            return <span style={{ color: '#94a3b8', fontSize: '12px' }}>{t('plugin.SecureHeaders.no_params')}</span>;
          }

          if (needsNameOnly.includes(filterType)) {
            return (
              <Input
                placeholder="Header/Parameter name"
                value={currentArgs}
                style={{
                  width: 200,
                  backgroundColor: 'rgba(0, 0, 0, 0.3)',
                  border: '1px solid rgba(148, 163, 184, 0.15)',
                  color: '#e2e8f0'
                }}
                onChange={(e) => {
                  setFieldValue(['filters', name, 'args'], e.target.value);
                }}
              />
            );
          }

          // Default: single input for StripPrefix, PrefixPath, SetPath, RequestSize, SetStatus
          return (
            <Input
              placeholder={filterType === 'StripPrefix' ? '1' : 
                          filterType === 'RequestSize' ? '10MB' :
                          filterType === 'SetStatus' ? '404' : 'value'}
              value={currentArgs}
              style={{
                width: 200,
                backgroundColor: 'rgba(0, 0, 0, 0.3)',
                border: '1px solid rgba(148, 163, 184, 0.15)',
                color: '#e2e8f0'
              }}
              onChange={(e) => {
                setFieldValue(['filters', name, 'args'], e.target.value);
              }}
            />
          );
        }}
      </Form.Item>

      <Form.Item noStyle>
        <Button 
          type="text" 
          danger 
          onClick={() => onRemove(name)}
          style={{ marginLeft: 8 }}
        >
          {t('common.remove')}
        </Button>
      </Form.Item>
    </div>
  );
};

export default FilterItem;
