/**
 * Load balancer strategy selector, shared by create/edit service forms.
 */
import React from 'react';
import { Select } from 'antd';
import {
  AimOutlined, ForkOutlined, LinkOutlined, ReloadOutlined,
  SwapOutlined, ThunderboltOutlined, TrophyOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const LoadBalancerSelect: React.FC = () => {
  const { t } = useTranslation();

  const options = [
    {
      value: 'weighted',
      icon: <TrophyOutlined className="option-icon option-icon-weighted" />,
      labelIcon: <TrophyOutlined className="option-icon" />,
      title: t('services.weighted'),
      desc: '平滑加权轮询，推荐',
    },
    {
      value: 'round-robin',
      icon: <SwapOutlined className="option-icon option-icon-roundrobin" />,
      labelIcon: <ReloadOutlined className="option-icon" />,
      title: t('services.round_robin'),
      desc: '简单轮询分发',
    },
    {
      value: 'random',
      icon: <AimOutlined className="option-icon option-icon-random" />,
      labelIcon: <ThunderboltOutlined className="option-icon" />,
      title: t('services.random'),
      desc: '随机选择',
    },
    {
      value: 'consistent-hash',
      icon: <ForkOutlined className="option-icon option-icon-hash" />,
      labelIcon: <LinkOutlined className="option-icon" />,
      title: t('services.consistent_hash'),
      desc: '基于哈希值，适合会话保持',
    },
  ];

  return (
    <Select
      size="large"
      className="select-with-icon"
      dropdownClassName="loadBalancer-dropdown"
      optionLabelProp="label"
      listHeight={280}
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
