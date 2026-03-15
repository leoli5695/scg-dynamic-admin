# Git Branch Management Workflow

## 🌿 Branch Structure

```
main (production-ready, protected)
  └── develop (integration branch for all features)
        ├── feature/* (new features)
        ├── bugfix/* (bug fixes)
        ├── hotfix/* (urgent production fixes)
        └── release/* (release preparation)
```

---

## 📋 Branch Rules

### **main 分支**
- ✅ **受保护分支** - 只能通过 Pull Request 合并
- ✅ **始终可发布** - 代码必须经过测试
- ✅ **打标签** - 每个版本都有对应的 tag
- ❌ **禁止直接 push** - 必须通过 develop 分支合并

### **develop 分支**
- ✅ **集成分支** - 所有功能开发完成后合并到这里
- ✅ **持续集成** - 自动运行测试
- ✅ **定期同步到 main** - 稳定后合并到 main
- ❌ **不是长期分支** - 最终会合并到 main

### **feature 分支**
- ✅ **从 develop 创建** - `git checkout -b feature/xxx develop`
- ✅ **命名规范** - `feature/add-oauth2`, `feature/improve-ui`
- ✅ **完成后的操作** - 合并回 develop，删除分支
- ❌ **不要长期存在** - 尽快完成并合并

### **bugfix 分支**
- ✅ **从 develop 创建** - 修复非紧急 bug
- ✅ **命名规范** - `bugfix/fix-login-issue`, `bugfix/rate-limit-error`
- ✅ **关联 Issue** - 在 commit message 中引用 issue 号

### **hotfix 分支**
- ✅ **从 main 创建** - 紧急修复生产环境问题
- ✅ **命名规范** - `hotfix/security-patch`, `hotfix/critical-bug`
- ✅ **双重合并** - 同时合并到 main 和 develop

---

## 🔄 Development Workflow

### **标准开发流程**

```bash
# 1. 从 develop 创建功能分支
git checkout develop
git pull origin develop
git checkout -b feature/new-authentication

# 2. 开发功能（多次 commit）
git add .
git commit -m "feat: add OAuth2 authentication support"

# 3. 同步 develop 的最新变更
git checkout develop
git pull origin develop
git checkout feature/new-authentication
git rebase develop

# 4. 推送到远程
git push -u origin feature/new-authentication

# 5. 在 GitHub 创建 Pull Request
#    https://github.com/leoli5695/scg-dynamic-admin/pulls
#    选择：feature/new-authentication → develop

# 6. Code Review 通过后合并到 develop
#    （在 GitHub 上点击 "Merge Pull Request"）

# 7. 删除功能分支
git branch -d feature/new-authentication
git push origin --delete feature/new-authentication
```

### **发布新版本流程**

```bash
# 1. 从 develop 创建 release 分支
git checkout develop
git pull origin develop
git checkout -b release/v1.1.0

# 2. 进行最后的测试和文档更新
#    （不添加新功能，只做最后调整）

# 3. 打标签
git tag -a v1.1.0 -m "Release version 1.1.0 - OAuth2 Support"

# 4. 合并到 main
git checkout main
git merge --no-ff release/v1.1.0
git push origin main
git push origin v1.1.0

# 5. 合并回 develop（确保同步）
git checkout develop
git merge --no-ff release/v1.1.0
git push origin develop

# 6. 删除 release 分支
git branch -d release/v1.1.0
```

### **紧急修复流程（Hotfix）**

```bash
# 1. 从 main 创建 hotfix 分支
git checkout main
git pull origin main
git checkout -b hotfix/security-patch

# 2. 修复紧急问题
git add .
git commit -m "fix: patch security vulnerability in JWT validation"

# 3. 合并到 main
git checkout main
git merge --no-ff hotfix/security-patch
git tag -a v1.0.1 -m "Hotfix for security vulnerability"
git push origin main
git push origin v1.0.1

# 4. 合并到 develop（保持同步）
git checkout develop
git merge --no-ff hotfix/security-patch
git push origin develop

# 5. 删除 hotfix 分支
git branch -d hotfix/security-patch
```

---

## 📝 Commit Message 规范

### **格式**
```
<type>(<scope>): <subject>

<body>

<footer>
```

### **Type 类型**
- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 文档更新
- `style`: 代码格式（不影响功能）
- `refactor`: 重构
- `perf`: 性能优化
- `test`: 测试相关
- `chore`: 构建工具、依赖管理等

### **示例**
```bash
# 新功能
git commit -m "feat(auth): add OAuth2 authentication support"

# Bug 修复
git commit -m "fix(rate-limiter): correct Redis connection timeout"

# 文档
git commit -m "docs(README): update installation instructions"

# 重构
git commit -m "refactor(discovery): simplify service discovery logic"

# 完整的 commit message
git commit -m "feat(gateway): implement weighted load balancing

Add weighted round-robin algorithm for static services.
Support dynamic weight adjustment via Admin API.

Closes #123"
```

---

## 🎯 Best Practices

### **✅ DO（建议做）**
- ✅ **小步提交** - 每个 commit 只做一件事
- ✅ **频繁同步** - 经常从 develop 拉取最新代码
- ✅ **及时清理** - 分支合并后立即删除
- ✅ **写清楚** - Commit message 要清晰描述变更
- ✅ **Code Review** - 所有合并都要有人 review
- ✅ **测试先行** - 重要功能先写测试

### **❌ DON'T（不要做）**
- ❌ **大爆炸提交** - 一次改几百个文件
- ❌ **长期分支** - 分支存在超过一周
- ❌ **直接推 main** - 绕过 Pull Request
- ❌ **模糊的 message** - "fix bug", "update code"
- ❌ **破坏性变更** - 不通知其他人就改接口
- ❌ **跳过测试** - 不测试就直接合并

---

## 🔧 Useful Commands

```bash
# 查看所有分支
git branch -a

# 查看远程分支
git branch -r

# 创建并切换到新分支
git checkout -b feature/my-feature

# 推送分支到远程
git push -u origin feature/my-feature

# 拉取远程分支
git fetch origin
git checkout feature/xxx

# 合并分支
git checkout main
git merge --no-ff develop

# 变基（保持线性历史）
git checkout feature/xxx
git rebase develop

# 删除本地分支
git branch -d feature/xxx

# 强制删除（未合并）
git branch -D feature/xxx

# 删除远程分支
git push origin --delete feature/xxx

# 查看提交历史（图形化）
git log --oneline --graph --all

# 暂存修改
git stash
git stash pop

# 查看某个文件的修改历史
git log -p filename.java
```

---

## 📊 Current Project Status

### **Branches**
- ✅ `main` - Production ready (latest: v1.0.0)
- ✅ `develop` - Development integration
- ⬜ `feature/*` - To be created for new features

### **Next Steps**
1. Create feature branches for upcoming enhancements
2. Follow the workflow for all new development
3. Use Pull Requests for code review
4. Tag releases on main branch

---

## 🚀 Quick Reference Card

```
┌─────────────────────────────────────────────┐
│  Feature Development                        │
│  develop → feature → PR → develop          │
├─────────────────────────────────────────────┤
│  Bug Fix                                    │
│  develop → bugfix → PR → develop           │
├─────────────────────────────────────────────┤
│  Release                                    │
│  develop → release → test → main + tag     │
├─────────────────────────────────────────────┤
│  Hotfix                                     │
│  main → hotfix → fix → main + tag → develop│
└─────────────────────────────────────────────┘
```

---

**Remember:** Good Git workflow = Less conflicts + Better quality + Easier maintenance! 💪
