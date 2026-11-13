/**
 * Load balancer strategy selector, shared by create/edit service forms.
 */
import React from 'react';
import { Select } from 'antd';
import type { SelectProps } from 'antd';
import {
  AimOutlined, ForkOutlined, LinkOutlined, ReloadOutlined,
  SwapOutlined, ThunderboltOutlined, TrophyOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const LoadBalancerSelect: React.FC<SelectProps> = (props) => {
  const { t } = useTranslation();

  const options = [
    {
      value: 'weighted',
      icon: <TrophyOutlined className="option-icon option-icon-weighted" />,
      labelIcon: <TrophyOutlined className="option-icon" />,
      title: t('services.weighted'),
      desc: t('services.weighted_desc'),
    },
    {
      value: 'round-robin',
      icon: <SwapOutlined className="option-icon option-icon-roundrobin" />,
      labelIcon: <ReloadOutlined className="option-icon" />,
      title: t('services.round_robin'),
      desc: t('services.round_robin_desc'),
    },
    {
      value: 'random',
      icon: <AimOutlined className="option-icon option-icon-random" />,
      labelIcon: <ThunderboltOutlined className="option-icon" />,
      title: t('services.random'),
      desc: t('services.random_desc'),
    },
    {
      value: 'consistent-hash',
      icon: <ForkOutlined className="option-icon option-icon-hash" />,
      labelIcon: <LinkOutlined className="option-icon" />,
      title: t('services.consistent_hash'),
      desc: t('services.consistent_hash_desc'),
    },
  ];

  return (
    <Select
      size="large"
      className="select-with-icon"
      classNames={{ popup: { root: "loadBalancer-dropdown" } }}
      optionLabelProp="label"
      listHeight={280}
      {...props}
    >
      {options.map((opt) => (
        <Select.Option
          key={opt.value}
          value={opt.value}
          label={(
            <span className="custom-select-label">
              {opt.labelIcon}
              <span className="option-title">{opt.title}</span>
            </span>
          )}
        >
          <span className="option-wrapper">
            {opt.icon}
            <span className="option-text">
              <span className="option-title">{opt.title}</span>
              <span className="option-desc">{opt.desc}</span>
            </span>
          </span>
        </Select.Option>
      ))}
    </Select>
  );
};

export default LoadBalancerSelect;
