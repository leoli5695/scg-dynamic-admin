# Gateway-UI 优化计划

> **前提**: 页面功能不能被修改，只做 CSS整合、性能优化、代码质量优化

> **★ 状态（截至 2026-10-28，逐条代码核实）**：本文是一次性优化计划，**主体已落地**，不随代码更新。
> - **已完成**：§1.1（颜色已统一）、§1.2（App.premium.css 精简 + `.bak` 已删）、§1.3（`styles/animations.css` 已建）、
>   §2.1（i18n 已按域拆分）、§2.2（懒加载 + Suspense 已做）、§3.1（`utils/instance.ts` 已建）、
>   §3.3（`: any` 已归零）。
> - **仍未完成**：§2.3 / §3.2 的 `src/components/common/`（该目录确实不存在；既有约定是 `src/components/ui/`）、
>   §3.4 的 `src/utils/errorHandling.ts`。
> - ⚠️ 本文中大量**文件路径、行号、体积数字已失效**（页面已从 `src/pages/` 重构为 `src/features/**`，
>   `InstanceDetailPage.tsx` / `utils/api.ts` / `useAbortableRequest.ts` 均已不存在）。
>   **照原文的"待办"逐条执行会重做或回退已有成果**，请先看每节的实际状态标注。

---

## 一、CSS 整合优化 (预计减少 ~150KB)

### 1.1 统一主题颜色定义（✅ 已完成）

> **实际状态**：两边**已经统一**，且统一值是 `#0a0f1e`（不是下表写的 `#09090b`）。
> `src/theme.ts:11` 是 `colorBgBase: '#0a0f1e'`，`src/styles/variables.css:25`（暗色）与 `:156` 是
> `--bg-base: #0a0f1e`，`:166`（亮色）是 `#ffffff`。下表的"当前值"是计划撰写时的旧快照。
> 注意 `theme.ts` 的 `colorBgBase` **保留**了（antd token 需要它），并未删除。

**问题**: `theme.ts` 和 `variables.css` 颜色定义不一致

| 文件 | 计划撰写时的值 | 实际现状 |
|------|--------|----------|
| theme.ts: `colorBgBase` | `#0a0f1c` | `#0a0f1e`（保留，已与 variables.css 一致） |
| variables.css: `--bg-base` | `#09090b` | `#0a0f1e`（暗色）/ `#ffffff`（亮色） |

**修改内容**:
- [x] `theme.ts`: 颜色已与 `variables.css` 统一为 `#0a0f1e`
- [x] theme.ts 中重复的颜色定义已清理，保留必要的 antd token

### 1.2 清理 App.premium.css 冗余样式（✅ 已完成）

**实际大小**: `git show HEAD:gateway-ui/src/App.premium.css | wc -c` = **143,791 字节（≈140KB）**。
计划撰写时写的是 237KB，"目标 ~80KB" 这个基线**已失效**——实际停在 ≈140KB，
且该文件仍是全站最大的单一 CSS，若还要继续瘦身需重新评估。

**冗余内容清单**（实测：`.glow-*`、`.gradient-*`、`.animate-pulse/.animate-glow`、`.hover-*`、
`.glass-effect*` 这些**类定义在 HEAD 中已全部为 0 命中**；硬编码 `#fbbf24` 也已 0 命中。
仅剩 6 处 `var(--gradient-primary)` 变量**引用**，属正常用法，不是待删的装饰类）:

| 类名/区域 | 行数估算 | 处理方式 |
|-----------|----------|----------|
| `.glow-*` 系列 | ~30行 | ✅ 已删除 |
| `.gradient-*` 系列 | ~40行 | ✅ 已删除 |
| `.animate-pulse`, `.animate-glow` | ~20行 | ✅ 已删除 |
| `.hover-scale`, `.hover-lift`, `.hover-glow` | ~15行 | ✅ 已删除 |
| `.glass-effect`, `.glass-effect-strong` | ~10行 | ✅ 已简化/删除 |
| 重复的 `.ant-modal-*` 定义 | ~200行 | ✅ 已合并到 antd-overrides.css |
| 重复的 `.ant-select-*` 定义 | ~150行 | ✅ 已合并到 antd-overrides.css |
| 黄色硬编码 `#fbbf24` | 全文件 | ✅ 已改用 `--accent-color` |
| 备用文件 `.bak` | - | ✅ 已删除（`git ls-files \| grep -i '\.bak'` 零结果） |

**具体操作**:
- [x] 删除 `.glow-primary`, `.glow-success`, `.glow-warning`, `.glow-error`
- [x] 删除 `.gradient-primary`, `.gradient-success`, `.gradient-warning`, `.gradient-danger`, `.gradient-card`
- [x] 删除 `.animate-pulse`, `.animate-glow`
- [x] 删除 `.hover-scale`, `.hover-lift`, `.hover-glow`
- [x] 简化 `.glass-effect` 系列
- [x] 合并 Modal 样式到 `antd-overrides.css`
- [x] 合并 Select 样式到 `antd-overrides.css`
- [x] 添加 `--accent-color: #fbbf24` 到 variables.css（实测在 `variables.css:13`）
- [x] 全文件替换 `#fbbf24` 为 `var(--accent-color)`
- [x] 删除 `KubernetesPage.professional.css.bak`

### 1.3 整理 CSS 文件结构

> ⚠️ **"当前结构"已完全失效**：`src/` 下**没有 `pages/` 目录**，也没有 `pages/*.css`。
> 页面样式已迁到 `src/features/**/*.css`（实测 23 个）。实测顶层为：
> `api/ components/ features/ hooks/ locales/ styles/ types/ utils/`
> \+ `App.tsx`、`App.css`、`App.premium.css`、`i18n.ts`、`index.css`、`main.tsx`、`theme.ts`、`vite-env.d.ts`。

**计划撰写时的结构**（已过期）:
```
src/
├── index.css (2KB) - 入口，导入其他 CSS
├── styles/
│   ├── variables.css (4KB) - CSS 变量
│   ├── layout.css (14KB) - 布局样式
│   ├── antd-overrides.css (15KB) - Antd 覆盖
│   ├── premium-ui.css (12KB) - 高级 UI 组件
├── App.css (5KB) - 应用样式
├── App.premium.css (238KB) - 冗余的大量样式   ← 实际 143,791 字节
└── pages/*.css - 页面专属样式                 ← 目录已不存在
```

**目标结构**（✅ 已按此落地，唯一差异是页面样式改放 `src/features/**`）:
```
src/
├── index.css - 入口，导入其他 CSS
├── styles/
│   ├── variables.css - CSS 变量（已含 --accent-color）
│   ├── layout.css - 布局样式
│   ├── antd-overrides.css - Antd 覆盖（已合并 modal/select 样式）
│   ├── premium-ui.css - 高级 UI 组件
│   ├── animations.css - ✅ 已存在
│   ├── ai-report.css / form-page.css - 后续新增
├── App.css - 应用样式
├── App.premium.css - 精简后（实际 ≈140KB，未达 ~80KB 目标）
└── features/**/*.css - 页面/域专属样式（取代原计划的 pages/*.css）
```

---

## 二、性能优化

### 2.1 拆分 i18n.ts（✅ 已完成，落地布局与设想不同）

> **实际状态**：`git show HEAD:gateway-ui/src/i18n.ts | wc -c` = **4,217 字节**，
> 不是下面写的 166KB。它现在只是配置入口，头部已 `import zhCommon from './locales/zh/common.json'` 等。
> 翻译文本已按域拆到 **`src/locales/en/` 与 `src/locales/zh/`，各 26 个 JSON**
> （accessLog / alert / audit / authPolicy / cert / common / copilot / diagnostic / filterChain /
> instance / jwtRevocation / k8s / login / menu / middleware / misc / monitor / plugins / replay /
> routes / services / strategy / stressTest / tokenUsage / topology / trace）。
>
> **与计划的唯一差异**：落地布局是 `src/i18n.ts` + `src/locales/{en,zh}/`，
> **不是** `src/i18n/index.ts` + `src/i18n/{en,zh}/`；域划分也比计划的 8 个更细（26 个）。
> **不要按下面的"目标结构"重建目录。**

**计划撰写时的现状**: 单文件 166KB，包含所有翻译

**计划设想的目标结构**（已按等价方案落地，见上方说明）:
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
│       └── （同 en/ 的 8 个文件）
```

**具体操作**:
- [x] 创建语言包目录（实际为 `src/locales/`）
- [x] i18n 配置文件（实际为 `src/i18n.ts`，未新建 `src/i18n/index.ts`）
- [x] 从 `i18n.ts` 提取各模块翻译到独立 JSON 文件（26 个域 × 2 语言）
- [ ] 配置 i18next 按需加载语言包（仍是静态 `import`，全部打进 bundle）
- [x] 更新 `main.tsx` 导入路径

### 2.2 实例详情子页面懒加载（✅ 已完成，文件已重构）

> **实际状态**：`InstanceDetailPage.tsx` **已不存在**，重构为
> `src/features/instance-detail/InstanceDetailLayout.tsx`。
> 该文件 `:37` 的注释是 "Outlet indirection kept tiny so Suspense wraps only tab content."，
> `:308-315` 已用 `<React.Suspense fallback={...}>` 包裹 tab 内容。
> 下面的 `InstanceDetailPage.tsx:52-68` 代码片段与 `renderTabContent` switch 写法均已失效。

**计划撰写时的现状**: 直接导入 16 个子页面

```tsx
// InstanceDetailPage.tsx:52-68 - 已不存在的旧代码
import ServicesPage from "./ServicesPage";
import RoutesPage from "./RoutesPage";
import StrategiesPage from "./StrategiesPage";
// ... 13 more imports
```

**具体操作**:
- [x] 子页面改为 `lazy()` 导入
- [x] 加载态 fallback
- [x] 用 `<Suspense>` 包裹 tab 内容（`InstanceDetailLayout.tsx:308-315`）
- [x] 测试各 Tab 切换功能正常

### 2.3 添加骨架屏组件（❌ 未实施；目标路径建议改为 `src/components/ui/`）

> **实际状态**：`src/components/common/` **确实不存在**。但仓库既有约定是
> **`src/components/ui/`**（现有 `FormModal.tsx`、`FormSection.tsx`、`form-ui.css`），
> 另有 `src/components/InlineStatChips.tsx` 与 `src/components/ai/`。
> 若要落地本项，**新组件应放 `src/components/ui/`**，不要再新建 `common/`。
> 下方"替换位置"里的 4 个 `*Page.tsx` 文件名也都已随重构消失（页面在 `src/features/**`）。

**创建文件**: `src/components/common/LoadingState.tsx`（建议改为 `src/components/ui/LoadingState.tsx`）

```tsx
// 预期代码结构
export const TableSkeleton = () => <Skeleton.Table active rows={5} />;
export const CardSkeleton = () => <Skeleton.Card active />;
export const TabLoading = () => <div className="tab-loading"><Spin /></div>;
export const PageLoading = () => <Skeleton active />;
```

**替换位置**（文件名已过期，仅作意图参考）:
- [ ] `InstancesPage.tsx` - 替换 `<Spin spinning={loading}>`
- [ ] `InstanceDetailPage.tsx` - 初始加载
- [ ] `ServicesPage.tsx` - 加载状态
- [ ] `RoutesPage.tsx` - 加载状态

### 2.4 vite.config.ts 优化 (已完成)

当前配置已有良好的 code splitting，保持不变。

> 补充：`vite.config.ts` 的 `build` 段现在用 `minify: 'terser'` +
> `pure_funcs: ['console.log','console.debug','console.trace','console.info']` 剥离噪音日志，
> **刻意不用 `drop_console`**（会连带删掉 `console.error/warn`）。详见
> `docs/archive/gateway-ui-console-cleanup.md`（原 `gateway-ui/CONSOLE_CLEANUP_GUIDE.md`，已归档）。

---

## 三、代码质量优化

### 3.1 提取公共逻辑函数（✅ 已完成）

> **实际状态**：`src/utils/instance.ts` **已存在**，下表 5 个函数 + `INSTANCE_STATUS` **全部已导出**：
> `INSTANCE_STATUS`(:9)、`getStatusColor`(:24)、`getStatusText`(:48)、`getSpecText`(:138)、
> `getEffectiveAccessUrl`(:168)、`getEffectiveNacosServerAddr`(:197)。
> 另外还多出 `getStatusColorByName`(:74)、`getStatusTextByName`(:101)，
> 以及类型 `InstanceStatusCode`(:17)、`InstanceConfig`(:126)、`InstanceWithAccessUrl`(:152)、
> `InstanceWithNacos`(:188)。**不要重复创建这个文件。**

**文件**: `src/utils/instance.ts`

**提取内容**（"来源文件"列的页面名已随重构过期）:

| 函数 | 来源文件 | 新位置 |
|------|----------|--------|
| `getStatusColor(statusCode)` | InstancesPage, InstanceDetailPage, 等9个文件 | ✅ `utils/instance.ts:24` |
| `getStatusText(statusCode, status)` | InstancesPage, InstanceDetailPage | ✅ `utils/instance.ts:48` |
| `getSpecText(instance)` | InstancesPage, InstanceDetailPage | ✅ `utils/instance.ts:138` |
| `getEffectiveAccessUrl(instance)` | InstanceDetailPage | ✅ `utils/instance.ts:168` |
| `getEffectiveNacosServerAddr(instance)` | InstanceDetailPage | ✅ `utils/instance.ts:197` |

**实际代码**（节选，与下方"预期代码"基本一致）:
```tsx
// src/utils/instance.ts
export const INSTANCE_STATUS = { /* STARTING/RUNNING/ERROR/STOPPING/STOPPED */ } as const;
export type InstanceStatusCode = typeof INSTANCE_STATUS[keyof typeof INSTANCE_STATUS];
export const getStatusColor = (statusCode: number): string => { /* ... */ };
export const getStatusColorByName = (status: string): string => { /* 计划外新增 */ };
export const getStatusText = (/* ... */): string => { /* ... */ };
export const getStatusTextByName = (/* ... */): string => { /* 计划外新增 */ };
export const getSpecText = (/* ... */): string => { /* ... */ };
export const getEffectiveAccessUrl = (instance: InstanceWithAccessUrl): string | null => { /* ... */ };
export const getEffectiveNacosServerAddr = (instance: InstanceWithNacos): string => { /* ... */ };
```

**具体操作**:
- [x] 创建 `src/utils/instance.ts`
- [x] 定义 `INSTANCE_STATUS` 常量
- [x] 提取 `getStatusColor` 函数
- [x] 提取 `getStatusText` 函数
- [x] 提取 `getSpecText` 函数
- [x] 更新使用方的导入

### 3.2 创建公共组件（❌ 未实施；目标路径建议改为 `src/components/ui/`）

> **实际状态**：`src/components/common/` **确实仍不存在**（这条待办为真）。
> 但既有约定是 **`src/components/ui/`**（`FormModal.tsx`、`FormSection.tsx`、`form-ui.css`），
> 另有 `src/components/InlineStatChips.tsx`、`src/components/ai/`。
> ⇒ **目标路径应改为 `src/components/ui/`**，避免引入第三套目录约定。

**创建目录**: `src/components/common/`（建议改为 `src/components/ui/`）

| 组件 | 用途 | 替换位置 |
|------|------|----------|
| `StatusBadge.tsx` | 状态标签显示 | InstancesPage, InstanceDetailPage 等 |
| `EmptyState.tsx` | 空数据状态 | 各列表页面 |
| `LoadingState.tsx` | 加载骨架屏 | 各页面加载状态 |
| `ErrorAlert.tsx` | 错误提示 | API 错误处理 |

**预期代码**:
```tsx
// src/components/ui/StatusBadge.tsx
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
（注：`getStatusColorByName` 在 `utils/instance.ts:74` 已存在，可直接用。）

**具体操作**:
- [ ] 创建 `src/components/ui/` 下的公共组件（目录本身已存在）
- [ ] 创建 `StatusBadge.tsx`
- [ ] 创建 `EmptyState.tsx`
- [ ] 创建 `LoadingState.tsx`
- [ ] 更新各页面使用新组件

### 3.3 修复 TypeScript 问题（✅ 已完成，且清单中的文件已不存在）

> **实际状态**：`git grep -o ": any" -- "src/**/*.ts" "src/**/*.tsx" | wc -l` = **0**。
> §六 里"25处 any → 0处" 的目标**已达成**。
> ⚠️ 下表前 3 行的定位**全部失效**：`api.ts`、`useAbortableRequest.ts` **两个文件都不存在**
> （API 层现为 `src/api/*.ts` 28 个 + `src/api/http.ts`）；HEAD 版 `src/App.tsx` grep
> `handleLogout` **零命中**。

**问题清单**（计划撰写时的快照）:

| 文件 | 行号 | 问题 | 现状 |
|------|------|------|----------|
| ~~`api.ts`~~ | ~~72~~ | `error: any` | 文件不存在；`: any` 全仓已归零 |
| ~~`useAbortableRequest.ts`~~ | ~~67~~ | `error: any` | 文件不存在；`: any` 全仓已归零 |
| ~~`App.tsx`~~ | ~~128-137~~ | `handleLogout` 缺少依赖 | HEAD 版 `App.tsx` 无 `handleLogout` |
| `RoutesPage.tsx` | 多处 | `predicates?: any[]` | ✅ 已消除 |
| `ServicesPage.tsx` | 多处 | `instances?: any[]` | ✅ 已消除 |
| 25个文件 | - | `: any` 类型 | ✅ 实测 0 处 |

**具体操作**:
- [x] 定义错误类型（替代 `error: any`）
- [x] 消除 API 层与 hook 的 any 类型
- [x] 定义 `Predicate` 等接口类型
- [x] 扫描并修复其他 `any` 类型（实测 `: any` = 0）

### 3.4 统一错误处理（❌ 未实施）

> **实际状态**：`src/utils/errorHandling.ts` **不存在**（`src/utils/` 只有
> `antdStatic.ts`、`instance.ts`、`logger.ts`、`serverTime.ts`）。这条待办为真。
> 下方示例代码本身有 bug（局部变量 `message` 与导入的 antd `message` 同名，会自我覆盖），
> 落地时需改名。替换位置里的页面名也已过期。

**创建文件**: `src/utils/errorHandling.ts`

```tsx
// 预期代码（注意：示例中 const message 与 antd 的 message 同名，实现时需改名）
export const handleApiError = (error: unknown, defaultMessage: string): void => {
  if (axios.isCancel(error)) return;

  const apiError = error as ApiError;
  const text = apiError.response?.data?.message || defaultMessage;
  message.error(text);
  logger.error('API Error:', apiError);   // 用 src/utils/logger.ts，勿直接 console.error
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

### Phase 1: CSS 整合 (低风险) — ✅ 已完成
1. ~~删除 `.bak` 文件~~ ✅
2. ~~删除遗留的装饰性类 (glow, gradient, animate)~~ ✅
3. ~~合并重复的 Antd 样式~~ ✅
4. ~~添加 `--accent-color` 并替换硬编码~~ ✅

### Phase 2: 性能优化 (中风险) — 部分完成
1. 创建骨架屏组件 ❌ 未做（建议放 `src/components/ui/`）
2. ~~拆分 i18n 文件~~ ✅
3. ~~实例详情懒加载改造~~ ✅（现为 `features/instance-detail/InstanceDetailLayout.tsx`）

### Phase 3: 代码质量 (中风险) — 部分完成
1. ~~创建 `utils/instance.ts` 提取公共函数~~ ✅
2. 创建公共组件目录 ❌ 未做（建议用既有 `src/components/ui/`，勿新建 `common/`）
3. ~~修复 TypeScript any 类型~~ ✅
4. 统一错误处理 ❌ 未做

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

| 项目 | 计划撰写时 | 目标 | 实测现状 |
|------|------|------|------|
| App.premium.css | 237KB（另处写 238KB） | ~80KB | **143,791 字节 ≈140KB**，未达 ~80KB |
| i18n.ts | 166KB | ~20KB入口 + 按需加载 | **4,217 字节**入口 + `src/locales/{en,zh}/` 各 26 个域 JSON；仍是静态 import，非按需加载 |
| 页面加载 | 直接导入 | 懒加载 | ✅ 已改懒加载 + `React.Suspense` |
| 代码重复 | 9处相同函数 | 1处共享 | ✅ 已收敛到 `src/utils/instance.ts` |
| TypeScript | 25处 any | 0处 any | ✅ `: any` 实测 **0** 处 |

**总体实际效果**: App.premium.css 由 237KB 降到 ≈140KB（约 -97KB，未达 -157KB 目标）；
i18n 入口由 166KB 降到 4KB；类型安全与代码复用目标已达成。