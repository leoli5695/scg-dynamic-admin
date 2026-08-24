/**
 * UserMenu — header avatar dropdown with logout.
 * Shared by the main shell and the instance-detail console header.
 */
import React, { useState } from 'react';
import { Avatar, Dropdown, Space } from 'antd';
import { message } from '../../utils/antdStatic';
import type { MenuProps } from 'antd';
import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';

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
      onClick: handleLogout,
    },
  ];

  return (
    <Dropdown
      menu={{ items }}
      placement="bottomRight"
      arrow
      trigger={['click']}
      open={open}
      onOpenChange={setOpen}
    >
      <Space className="user-info" style={{ cursor: 'pointer' }}>
        <Avatar
          style={{ backgroundColor: '#165DFF' }}
          icon={<UserOutlined />}
          size="small"
        />
        <span className="username">{user.nickname || user.username}</span>
      </Space>
    </Dropdown>
  );
};

export default UserMenu;
