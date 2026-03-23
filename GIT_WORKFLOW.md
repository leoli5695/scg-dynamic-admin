# Git Branch Management Workflow

## Branch Structure

```
main (production-ready, protected)
  └── develop (integration branch for all features)
        ├── feature/* (new features)
        ├── bugfix/* (bug fixes)
        ├── hotfix/* (urgent production fixes)
        └── release/* (release preparation)
```

---

## Branch Rules

### **main branch**
- **Protected branch** - Merges only via Pull Request
- **Always deployable** - Code must pass all tests
- **Tagged releases** - Each version has a corresponding tag
- **No direct push** - Must merge through develop branch

### **develop branch**
- **Integration branch** - All completed features merge here
- **Continuous Integration** - Automated tests run on every push
- **Periodic sync to main** - Merged to main when stable
- **Not long-lived** - Eventually merged to main

### **feature branch**
- **Created from develop** - `git checkout -b feature/xxx develop`
- **Naming convention** - `feature/add-oauth2`, `feature/improve-ui`
- **After completion** - Merge back to develop, delete branch
- **Don't keep long** - Complete and merge quickly

### **bugfix branch**
- **Created from develop** - Fix non-urgent bugs
- **Naming convention** - `bugfix/fix-login-issue`, `bugfix/rate-limit-error`
- **Link Issue** - Reference issue number in commit message

### **hotfix branch**
- **Created from main** - Urgent production fixes
- **Naming convention** - `hotfix/security-patch`, `hotfix/critical-bug`
- **Dual merge** - Merge to both main and develop

---

## Development Workflow

### **Standard Feature Development**

```bash
# 1. Create feature branch from develop
git checkout develop
git pull origin develop
git checkout -b feature/new-authentication

# 2. Develop feature (multiple commits)
git add .
git commit -m "feat: add OAuth2 authentication support"

# 3. Sync latest changes from develop
git checkout develop
git pull origin develop
git checkout feature/new-authentication
git rebase develop

# 4. Push to remote
git push -u origin feature/new-authentication

# 5. Create Pull Request on GitHub
#    https://github.com/leoli5695/scg-dynamic-admin/pulls
#    Select: feature/new-authentication -> develop

# 6. After Code Review approval, merge to develop
#    (Click "Merge Pull Request" on GitHub)

# 7. Delete feature branch
git branch -d feature/new-authentication
git push origin --delete feature/new-authentication
```

### **Release New Version**

```bash
# 1. Create release branch from develop
git checkout develop
git pull origin develop
git checkout -b release/v1.1.0

# 2. Final testing and documentation updates
#    (No new features, only final adjustments)

# 3. Create tag
git tag -a v1.1.0 -m "Release version 1.1.0 - OAuth2 Support"

# 4. Merge to main
git checkout main
git merge --no-ff release/v1.1.0
git push origin main
git push origin v1.1.0

# 5. Merge back to develop (keep in sync)
git checkout develop
git merge --no-ff release/v1.1.0
git push origin develop

# 6. Delete release branch
git branch -d release/v1.1.0
```

### **Hotfix Workflow**

```bash
# 1. Create hotfix branch from main
git checkout main
git pull origin main
git checkout -b hotfix/security-patch

# 2. Fix the urgent issue
git add .
git commit -m "fix: patch security vulnerability in JWT validation"

# 3. Merge to main
git checkout main
git merge --no-ff hotfix/security-patch
git tag -a v1.0.1 -m "Hotfix for security vulnerability"
git push origin main
git push origin v1.0.1

# 4. Merge to develop (keep in sync)
git checkout develop
git merge --no-ff hotfix/security-patch
git push origin develop

# 5. Delete hotfix branch
git branch -d hotfix/security-patch
```

---

## Commit Message Convention

### **Format**
```
<type>(<scope>): <subject>

<body>

<footer>
```

### **Types**
| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation update |
| `style` | Code formatting (no functionality change) |
| `refactor` | Code refactoring |
| `perf` | Performance optimization |
| `test` | Test-related changes |
| `chore` | Build tools, dependencies, etc. |

### **Examples**
```bash
# New feature
git commit -m "feat(auth): add OAuth2 authentication support"

# Bug fix
git commit -m "fix(rate-limiter): correct Redis connection timeout"

# Documentation
git commit -m "docs(README): update installation instructions"

# Refactoring
git commit -m "refactor(discovery): simplify service discovery logic"

# Complete commit message
git commit -m "feat(gateway): implement weighted load balancing

Add weighted round-robin algorithm for static services.
Support dynamic weight adjustment via Admin API.

Closes #123"
```

---

## Best Practices

### **DO**
- **Small commits** - Each commit does one thing
- **Frequent sync** - Pull latest code from develop often
- **Clean up** - Delete branches immediately after merge
- **Clear messages** - Commit messages clearly describe changes
- **Code Review** - All merges require review
- **Test first** - Write tests for important features

### **DON'T**
- **Big bang commits** - Hundreds of files in one commit
- **Long-lived branches** - Branches existing over a week
- **Direct push to main** - Bypassing Pull Request
- **Vague messages** - "fix bug", "update code"
- **Breaking changes** - Changing interfaces without notice
- **Skip tests** - Merging without testing

---

## Useful Commands

```bash
# List all branches
git branch -a

# List remote branches
git branch -r

# Create and switch to new branch
git checkout -b feature/my-feature

# Push branch to remote
git push -u origin feature/my-feature

# Fetch remote branches
git fetch origin
git checkout feature/xxx

# Merge branch
git checkout main
git merge --no-ff develop

# Rebase (keep linear history)
git checkout feature/xxx
git rebase develop

# Delete local branch
git branch -d feature/xxx

# Force delete (unmerged)
git branch -D feature/xxx

# Delete remote branch
git push origin --delete feature/xxx

# View commit history (graph)
git log --oneline --graph --all

# Stash changes
git stash
git stash pop

# View file change history
git log -p filename.java
```

---

## Current Project Status

### **Branches**
- `main` - Production ready (latest: v1.0.0)
- `develop` - Development integration
- `feature/*` - To be created for new features

### **Next Steps**
1. Create feature branches for upcoming enhancements
2. Follow the workflow for all new development
3. Use Pull Requests for code review
4. Tag releases on main branch

---

## Quick Reference Card

```
+---------------------------------------------+
|  Feature Development                        |
|  develop -> feature -> PR -> develop        |
+---------------------------------------------+
|  Bug Fix                                    |
|  develop -> bugfix -> PR -> develop         |
+---------------------------------------------+
|  Release                                    |
|  develop -> release -> test -> main + tag   |
+---------------------------------------------+
|  Hotfix                                     |
|  main -> hotfix -> fix -> main + tag -> develop|
+---------------------------------------------+
```

---

**Remember:** Good Git workflow = Less conflicts + Better quality + Easier maintenance!