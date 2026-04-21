import { Select } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const LanguageSwitcher: React.FC = () => {
  const { i18n } = useTranslation();

  const languages = [
    { value: 'en', label: 'English' },
    { value: 'zh', label: '中文' },
  ];

  const handleChange = (value: string) => {
    i18n.changeLanguage(value);
  };

  // Get current language code (first 2 characters)
  const currentLang = i18n.language?.substring(0, 2) || 'en';

  return (
    <Select
      value={currentLang}
      onChange={handleChange}
      options={languages}
      popupClassName="language-switcher-dropdown"
      style={{ width: 120 }}
      suffixIcon={<GlobalOutlined />}
    />
  );
};

export default LanguageSwitcher;
