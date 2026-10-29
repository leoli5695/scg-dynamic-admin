/**
 * UserMenu — header avatar dropdown with logout.
 * Shared by the main shell and the instance-detail console header.
 */
import React, { useState } from 'react';
import { Avatar, Dropdown } from 'antd';
import { message } from '../../utils/antdStatic';
import type { MenuProps } from 'antd';
import { DownOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import './header-actions.css';

const UserMenu: React.FC = () => {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const { t } = useTranslation();

  if (!user) return null;

  const handleLogout = async () => {
    setOpen(false);
    await logout();
    message.success(t('common.logout_success', 'Logout successful'));
    navigate('/login', { replace: true });
  };

  const items: MenuProps['items'] = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('common.logout'),
      danger: true,
      onClick: handleLogout,
    },
  ];

  return (
    <Dropdown
      menu={{ items }}
      classNames={{ root: 'console-account-popup' }}
      popupRender={(menu) => <><div className="console-account-summary"><strong>{user.nickname || user.username}</strong>{user.nickname && user.nickname !== user.username && <span>{user.username}</span>}</div>{menu}</>}
      placement="bottomRight"
      trigger={['click']}
      open={open}
      onOpenChange={setOpen}
    >
      <button type="button" className="user-info console-account" aria-label={`${t('common.account')}: ${user.nickname || user.username}`} aria-haspopup="menu" aria-expanded={open}>
        <Avatar
          className="console-account-avatar"
          icon={<UserOutlined />}
          size="small"
        />
        <span className="username">{user.nickname || user.username}</span>
        <DownOutlined className="console-account-chevron" />
      </button>
    </Dropdown>
  );
};

export default UserMenu;
