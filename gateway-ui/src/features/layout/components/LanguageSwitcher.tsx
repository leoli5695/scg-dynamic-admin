import { Select } from 'antd';
import { CheckOutlined, DownOutlined, GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import '../header-actions.css';

const LanguageSwitcher: React.FC = () => {
  const { i18n, t } = useTranslation();

  const languages = [
    { value: 'zh', label: '中文', description: 'Simplified Chinese' },
    { value: 'en', label: 'English', description: '英语' },
  ];

  const handleChange = (value: string) => {
    if (value !== currentLang) void i18n.changeLanguage(value);
  };

  // Get current language code (first 2 characters)
  const currentLang = (i18n.resolvedLanguage || i18n.language)?.startsWith('zh') ? 'zh' : 'en';

  return (
    <Select
      className="console-language-switcher"
      aria-label={t('common.language')}
      value={currentLang}
      onChange={handleChange}
      options={languages}
      classNames={{ popup: { root: 'console-language-popup' } }}
      placement="bottomRight"
      popupMatchSelectWidth={196}
      virtual={false}
      prefix={<GlobalOutlined />}
      suffixIcon={<DownOutlined />}
      menuItemSelectedIcon={<CheckOutlined />}
      optionRender={(option) => (
        <span className="console-language-option" lang={option.value === 'zh' ? 'zh-CN' : 'en'}>
          <span>{option.label}</span>
          <small>{option.data.description}</small>
        </span>
      )}
      popupRender={(menu) => <><div className="console-popup-heading">语言 / Language</div>{menu}</>}
    />
  );
};

export default LanguageSwitcher;
