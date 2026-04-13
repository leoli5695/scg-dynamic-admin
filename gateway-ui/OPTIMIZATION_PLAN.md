# Gateway-UI 优化计划

> **前提**: 页面功能不能被修改，只做 CSS整合、性能优化、代码质量优化

---

## 一、CSS 整合优化 (预计减少 ~150KB)

### 1.1 统一主题颜色定义

**问题**: `theme.ts` 和 `variables.css` 颜色定义不一致

| 文件 | 当前值 | 应统一为 |
|------|--------|----------|
| theme.ts: `colorBgBase` | `#0a0f1c` | 删除，改用 CSS 变量 |
| variables.css: `--bg-base` | `#09090b` | 保持不变 (标准) |

**修改内容**:
- [ ] `theme.ts`: 删除硬编码颜色，改用 CSS 变量引用或统一为 `#09090b`
- [ ] 删除 theme.ts 中重复的颜色定义，保留必要的 antd token

### 1.2 清理 App.premium.css 冗余样式

**当前大小**: 237KB → **目标**: ~80KB

**冗余内容清单**:

| 类名/区域 | 行数估算 | 处理方式 |
|-----------|----------|----------|
| `.glow-*` 系列 | ~30行 | 删除 (已被 `.card-base` 替代) |
| `.gradient-*` 系列 | ~40行 | 删除 (已禁用) |
| `.animate-pulse`, `.animate-glow` | ~20行 | 删除 (animation: none) |
| `.hover-scale`, `.hover-lift`, `.hover-glow` | ~15行 | 删除 (transform: none) |
| `.glass-effect`, `.glass-effect-strong` | ~10行 | 简化 (已简化为普通卡片) |
| 重复的 `.ant-modal-*` 定义 | ~200行 | 合并到 antd-overrides.css |
| 重复的 `.ant-select-*` 定义 | ~150行 | 合并到 antd-overrides.css |
| 黄色硬编码 `#fbbf24` | 全文件 | 改用 CSS 变量 `--accent-color` |
| 备用文件 `.bak` | - | 删除 `KubernetesPage.professional.css.bak` |

**具体操作**:
- [ ] 删除 `.glow-primary`, `.glow-success`, `.glow-warning`, `.glow-error`
- [ ] 删除 `.gradient-primary`, `.gradient-success`, `.gradient-warning`, `.gradient-danger`, `.gradient-card`
- [ ] 删除 `.animate-pulse`, `.animate-glow`
- [ ] 删除 `.hover-scale`, `.hover-lift`, `.hover-glow`
- [ ] 简化 `.glass-effect` 系列
- [ ] 合并 Modal 样式到 `antd-overrides.css`
- [ ] 合并 Select 样式到 `antd-overrides.css`
- [ ] 添加 `--accent-color: #fbbf24` 到 variables.css
- [ ] 全文件替换 `#fbbf24` 为 `var(--accent-color)`
- [ ] 删除 `KubernetesPage.professional.css.bak`

### 1.3 整理 CSS 文件结构

**当前结构**:
```
src/
├── index.css (2KB) - 入口，导入其他 CSS
├── styles/
│   ├── variables.css (4KB) - CSS 变量
│   ├── layout.css (14KB) - 布局样式
│   ├── antd-overrides.css (15KB) - Antd 覆盖
│   ├── premium-ui.css (12KB) - 高级 UI 组件
├── App.css (5KB) - 应用样式
├── App.premium.css (238KB) - 冗余的大量样式
└── pages/*.css - 页面专属样式
```

**目标结构**:
```
src/
├── index.css - 入口，导入其他 CSS
├── styles/
│   ├── variables.css - CSS 变量 (添加 --accent-color)
│   ├── layout.css - 布局样式 (保持)
│   ├── antd-overrides.css - Antd 覆盖 (合并 modal/select 样式)
│   ├── premium-ui.css - 高级 UI 组件 (保持)
│   └── animations.css - 新增：保留的动画 (fade-in, slide-up)
├── App.css - 应用样式 (保持)
├── App.premium.css - 精简后 (~80KB)
└── pages/*.css - 页面专属样式 (保持)
```

---

## 二、性能优化

### 2.1 拆分 i18n.ts (166KB → 多个小文件)

**当前**: 单文件 166KB，包含所有翻译

**目标结构**:
```
src/
├── i18n/
│   ├── index.ts - i18n 配置入口
│   ├── en/
│   │   ├── common.json - 通用翻译 (~50行)
│   │   ├── menu.json - 菜单翻译 (~30行)
│   │   ├── routes.json - 路由相关 (~80行)
│   │   ├── services.json - 服务相关 (~40行)
│   │   ├── instance.json - 实例相关 (~60行)
│   │   ├── strategies.json - 策略相关 (~50行)
│   │   ├── plugin.json - 插件翻译 (~60行)
│   │   └── pages.json - 其他页面 (~100行)
│   └── zh/
│   │   ├── common.json
│   │   ├── menu.json
│   │   ├── routes.json
│   │   ├── services.json
│   │   ├── instance.json
│   │   ├── strategies.json
│   │   ├── plugin.json
│   │   └── pages.json
```

**具体操作**:
- [ ] 创建 `src/i18n/` 目录
- [ ] 创建 `src/i18n/index.ts` 配置文件
- [ ] 从 `i18n.ts` 提取各模块翻译到独立 JSON 文件
- [ ] 配置 i18next 按需加载语言包
- [ ] 更新 `main.tsx` 导入路径

### 2.2 InstanceDetailPage 子页面懒加载

**当前**: 直接导入 16 个子页面

```tsx
// InstanceDetailPage.tsx:52-68 - 当前代码
import ServicesPage from "./ServicesPage";
import RoutesPage from "./RoutesPage";
import StrategiesPage from "./StrategiesPage";
// ... 13 more imports
```

**修改为**:
```tsx
// 懒加载子页面
const ServicesPage = lazy(() => import("./ServicesPage"));
const RoutesPage = lazy(() => import("./RoutesPage"));
const StrategiesPage = lazy(() => import("./StrategiesPage"));
// ... 其他页面

// 渲染时包裹 Suspense
const renderTabContent = () => {
  switch (activeTab) {
    case "services":
      return <Suspense fallback={<TabLoading />}><ServicesPage instanceId={instance.instanceId} /></Suspense>;
    // ...
  }
};
```

**具体操作**:
- [ ] 将 16 个子页面改为 `lazy()` 导入
- [ ] 创建 `TabLoading` 组件 (骨架屏)
- [ ] 在 `renderTabContent` 中包裹 `<Suspense>`
- [ ] 测试各 Tab 切换功能正常

### 2.3 添加骨架屏组件

**创建文件**: `src/components/common/LoadingState.tsx`

```tsx
// 预期代码结构
export const TableSkeleton = () => <Skeleton.Table active rows={5} />;
export const CardSkeleton = () => <Skeleton.Card active />;
export const TabLoading = () => <div className="tab-loading"><Spin /></div>;
export const PageLoading = () => <Skeleton active />;
```

**替换位置**:
- [ ] `InstancesPage.tsx` - 替换 `<Spin spinning={loading}>`
- [ ] `InstanceDetailPage.tsx` - 初始加载
- [ ] `ServicesPage.tsx` - 加载状态
- [ ] `RoutesPage.tsx` - 加载状态

### 2.4 vite.config.ts 优化 (已完成)

当前配置已有良好的 code splitting，保持不变。

---

## 三、代码质量优化

### 3.1 提取公共逻辑函数

**创建文件**: `src/utils/instance.ts`

**提取内容**:

| 函数 | 来源文件 | 新位置 |
|------|----------|--------|
| `getStatusColor(statusCode)` | InstancesPage, InstanceDetailPage, 等9个文件 | `utils/instance.ts` |
| `getStatusText(statusCode, status)` | InstancesPage, InstanceDetailPage | `utils/instance.ts` |
| `getSpecText(instance)` | InstancesPage, InstanceDetailPage | `utils/instance.ts` |
| `getEffectiveAccessUrl(instance)` | InstanceDetailPage | `utils/instance.ts` |
| `getEffectiveNacosServerAddr(instance)` | InstanceDetailPage | `utils/instance.ts` |

**预期代码**:
```tsx
// src/utils/instance.ts
export const INSTANCE_STATUS = {
  STARTING: 0,
  RUNNING: 1,
  ERROR: 2,
  STOPPING: 3,
  STOPPED: 4,
} as const;

export const getStatusColor = (statusCode: number): string => {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING: return 'green';
    case INSTANCE_STATUS.STARTING: return 'blue';
    case INSTANCE_STATUS.STOPPED: return 'orange';
    case INSTANCE_STATUS.ERROR: return 'red';
    case INSTANCE_STATUS.STOPPING: return 'purple';
    default: return 'default';
  }
};

export const getStatusText = (statusCode: number, status: string, t: (key: string) => string): string => {
  // ...
};

export const getSpecText = (instance: GatewayInstance, t: (key: string) => string): string => {
  // ...
};
```

**具体操作**:
- [ ] 创建 `src/utils/instance.ts`
- [ ] 定义 `INSTANCE_STATUS` 常量
- [ ] 提取 `getStatusColor` 函数
- [ ] 提取 `getStatusText` 函数
- [ ] 提取 `getSpecText` 函数
- [ ] 更新 9 个使用文件的导入

### 3.2 创建公共组件

**创建目录**: `src/components/common/`

| 组件 | 用途 | 替换位置 |
|------|------|----------|
| `StatusBadge.tsx` | 状态标签显示 | InstancesPage, InstanceDetailPage 等 |
| `EmptyState.tsx` | 空数据状态 | 各列表页面 |
| `LoadingState.tsx` | 加载骨架屏 | 各页面加载状态 |
| `ErrorAlert.tsx` | 错误提示 | API 错误处理 |

**预期代码**:
```tsx
// src/components/common/StatusBadge.tsx
interface StatusBadgeProps {
  status: number | string;
  text?: string;
  size?: 'small' | 'default';
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, text, size }) => {
  const color = typeof status === 'number' ? getStatusColor(status) : getStatusColorByName(status);
  return <Tag color={color} size={size}>{text}</Tag>;
};
```

**具体操作**:
- [ ] 创建 `src/components/common/` 目录
- [ ] 创建 `StatusBadge.tsx`
- [ ] 创建 `EmptyState.tsx`
- [ ] 创建 `LoadingState.tsx`
- [ ] 更新各页面使用新组件

### 3.3 修复 TypeScript 问题

**问题清单**:

| 文件 | 行号 | 问题 | 修复方式 |
|------|------|------|----------|
| `api.ts` | 72 | `error: any` | 定义 `ApiError` 类型 |
| `useAbortableRequest.ts` | 67 | `error: any` | 定义 `ApiError` 类型 |
| `App.tsx` | 128-137 | `handleLogout` 缺少依赖 | 使用 `useCallback` 包裹 |
| `RoutesPage.tsx` | 多处 | `predicates?: any[]` | 定义 `Predicate` 接口 |
| `ServicesPage.tsx` | 多处 | `instances?: any[]` | 已有类型，修复导入 |
| 25个文件 | - | `: any` 类型 | 逐一替换为具体类型 |

**具体操作**:
- [ ] 定义 `ApiError` 类型
- [ ] 修复 `api.ts` 和 `useAbortableRequest.ts` 的 any 类型
- [ ] 修复 `App.tsx` 的 `handleLogout` useCallback
- [ ] 定义 `Predicate` 接口类型
- [ ] 扫描并修复其他 `any` 类型 (优先级按文件大小排序)

### 3.4 统一错误处理

**创建文件**: `src/utils/errorHandling.ts`

```tsx
// 预期代码
export const handleApiError = (error: unknown, defaultMessage: string): void => {
  if (axios.isCancel(error)) return;
  
  const apiError = error as ApiError;
  const message = apiError.response?.data?.message || defaultMessage;
  message.error(message);
  console.error('API Error:', apiError);
};
```

**替换位置**:
- [ ] InstancesPage 的 catch 块
- [ ] InstanceDetailPage 的 catch 块
- [ ] ServicesPage 的 catch 块
- [ ] RoutesPage 的 catch 块
- [ ] 其他页面的 catch 块

---

## 四、执行顺序

### Phase 1: CSS 整合 (低风险)
1. 删除 `.bak` 文件
2. 删除遗留的装饰性类 (glow, gradient, animate)
3. 合并重复的 Antd 样式
4. 添加 `--accent-color` 并替换硬编码

### Phase 2: 性能优化 (中风险)
1. 创建骨架屏组件
2. 拆分 i18n 文件
3. InstanceDetailPage 懒加载改造

### Phase 3: 代码质量 (中风险)
1. 创建 `utils/instance.ts` 提取公共函数
2. 创建 `components/common/` 公共组件
3. 修复 TypeScript any 类型
4. 统一错误处理

---

## 五、验证清单

每阶段完成后验证:
- [ ] 所有页面功能正常
- [ ] 样式显示正确
- [ ] 语言切换正常
- [ ] Tab 切换正常
- [ ] API 调用正常
- [ ] 错误处理正常
- [ ] TypeScript 编译无错误

---

## 六、预估效果

| 项目 | 当前 | 目标 | 效果 |
|------|------|------|------|
| App.premium.css | 237KB | ~80KB | 减少 157KB |
| i18n.ts | 166KB | ~20KB入口 + 按需加载 | 初始减少 ~146KB |
| 页面加载 | 直接导入 | 懒加载 | 首屏更快 |
| 代码重复 | 9处相同函数 | 1处共享 | 维护性提升 |
| TypeScript | 25处 any | 0处 any | 类型安全 |

**总体预估**: 首屏 JS/CSS 减少 ~300KB，维护性和类型安全性显著提升