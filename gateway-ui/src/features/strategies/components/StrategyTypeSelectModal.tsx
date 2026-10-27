import React, { useMemo, useState } from 'react';
import { Button, Empty, Input, Modal, Segmented, Spin } from 'antd';
import { ArrowRightOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StrategyTypeInfo } from '../../../types/strategy';
import { CATEGORY_LABELS } from '../utils';
import { filterStrategyTypes, getCreatableStrategyTypes } from '../strategyPicker';
import type { StrategyPickerTab } from '../strategyPicker';
import { getStrategyTypeIcon } from './icons';
import './strategy-picker.css';

interface StrategyTypeSelectModalProps {
  strategyTypes: StrategyTypeInfo[];
  initialTab?: 'general' | 'ai';
  loading: boolean;
  onSelect: (type: StrategyTypeInfo) => void;
  onCancel: () => void;
}

const StrategyTypeSelectModal: React.FC<StrategyTypeSelectModalProps> = ({
  strategyTypes,
  initialTab = 'general',
  loading,
  onSelect,
  onCancel,
}) => {
  const { t } = useTranslation();
  const [tab, setTab] = useState<StrategyPickerTab>(initialTab);
  const [category, setCategory] = useState('all');
  const [query, setQuery] = useState('');
  const types = useMemo(() => getCreatableStrategyTypes(strategyTypes), [strategyTypes]);
  const categoryLabel = (value: string) =>
    t(`strategy.picker_category_${value}`, {
      defaultValue: CATEGORY_LABELS[value] || value,
    });
  const scopedTypes = filterStrategyTypes(types, { tab });
  const categories = Array.from(new Set(scopedTypes.map((type) => type.category)));
  const results = filterStrategyTypes(types, { tab, category, query }, categoryLabel);
  const generalCount = types.filter((type) => type.category !== 'ai').length;
  const aiCount = types.length - generalCount;
  const reset = () => {
    setQuery('');
    setCategory('all');
    setTab('all');
  };

  return (
    <Modal
      open
      onCancel={onCancel}
      footer={null}
      width={1120}
      className="console-picker strategy-type-select-modal strategy-picker"
      title={
        <div className="modal-title-text">
          <div className="modal-title">{t('strategy.select_type')}</div>
          <div className="modal-subtitle">{t('strategy.picker_hint')}</div>
        </div>
      }
    >
      <div className="strategy-picker-toolbar">
        <div className="strategy-picker-search-row">
          <Segmented
            aria-label={t('strategy.type')}
            value={tab}
            onChange={(value) => {
              setTab(value as StrategyPickerTab);
              setCategory('all');
            }}
            options={[
              { value: 'all', label: `${t('strategy.tab_all')} ${types.length}` },
              { value: 'general', label: `${t('strategy.tab_normal')} ${generalCount}` },
              { value: 'ai', label: `${t('strategy.tab_ai')} ${aiCount}` },
            ]}
          />
          <Input
            autoFocus
            allowClear
            prefix={<SearchOutlined />}
            value={query}
            aria-label={t('strategy.picker_search')}
            placeholder={t('strategy.picker_search')}
            onChange={(event) => {
              const value = event.target.value;
              // Starting a search includes AI types, so users need not guess a tab first.
              if (!query.trim() && value.trim()) {
                setTab('all');
                setCategory('all');
              }
              setQuery(value);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && query) {
                event.stopPropagation();
                setQuery('');
              }
            }}
            onPressEnter={() => {
              if (!loading && results.length === 1) onSelect(results[0]);
            }}
          />
        </div>
        <div
          className="strategy-picker-categories"
          role="group"
          aria-label={t('strategy.picker_category')}
        >
          {[
            { value: 'all', label: t('common.all'), count: scopedTypes.length },
            ...categories.map((value) => ({
              value,
              label: categoryLabel(value),
              count: scopedTypes.filter((type) => type.category === value).length,
            })),
          ].map((item) => (
            <button
              type="button"
              key={item.value}
              aria-pressed={category === item.value}
              onClick={() => setCategory(item.value)}
            >
              {item.label}
              <span>{item.count}</span>
            </button>
          ))}
        </div>
        <div className="strategy-picker-result-count" role="status" aria-live="polite">
          {t('strategy.picker_results', { count: results.length })}
          {(query || category !== 'all') && (
            <Button type="link" size="small" onClick={reset}>
              {t('strategy.picker_reset')}
            </Button>
          )}
        </div>
      </div>
      <div className="strategy-picker-results">
        <Spin spinning={loading}>
          {results.length === 0 && !loading ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('strategy.picker_empty')}>
              <Button onClick={reset}>{t('strategy.picker_reset')}</Button>
            </Empty>
          ) : (
            <div className="strategy-picker-grid">
              {results.map((type) => (
                <button
                  type="button"
                  className="strategy-picker-card"
                  key={type.typeCode}
                  disabled={loading}
                  onClick={() => onSelect(type)}
                  title={type.description || type.typeName}
                  aria-label={`${type.typeName} · ${type.typeNameEn}`}
                >
                  <span className="strategy-picker-card-heading">
                    <span
                      className="strategy-picker-icon"
                      style={{
                        color: type.color || '#9ebcff',
                        background: `${type.color || '#819feb'}18`,
                      }}
                    >
                      {getStrategyTypeIcon(type.icon)}
                    </span>
                    <span className="strategy-picker-names">
                      <strong>{type.typeName}</strong>
                      <span>{type.typeNameEn || type.typeCode}</span>
                    </span>
                    <ArrowRightOutlined className="strategy-picker-arrow" />
                  </span>
                  <span className="strategy-picker-card-footer">
                    <span>{type.description || type.typeCode}</span>
                    <small>{categoryLabel(type.category)}</small>
                  </span>
                </button>
              ))}
            </div>
          )}
        </Spin>
      </div>
    </Modal>
  );
};

export default StrategyTypeSelectModal;
