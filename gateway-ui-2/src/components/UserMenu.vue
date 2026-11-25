<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Avatar, Dropdown, Menu, MenuItem, Space } from 'ant-design-vue'
import { LogoutOutlined, UserOutlined } from '@/components/icons'
import { useAuthStore } from '@/stores/auth'
import { message } from '@/utils/feedback'

const auth = useAuthStore()
const router = useRouter()
const { t } = useI18n()
const open = ref(false)

async function handleLogout() {
  open.value = false
  await auth.logout()
  message.success(t('common.logout_success'))
  router.replace('/login')
}
</script>

<template>
  <Dropdown
    v-if="auth.user"
    v-model:open="open"
    placement="bottomRight"
    :trigger="['click']"
  >
    <template #overlay>
      <Menu>
        <MenuItem key="logout" @click="handleLogout">
          <LogoutOutlined />
          <span style="margin-left: 8px">{{ t('common.logout') }}</span>
        </MenuItem>
      </Menu>
    </template>
    <Space class="user-info" :size="8">
      <Avatar class="user-avatar" size="small">
        <template #icon><UserOutlined /></template>
      </Avatar>
      <span class="username">{{ auth.user.nickname || auth.user.username }}</span>
    </Space>
  </Dropdown>
</template>