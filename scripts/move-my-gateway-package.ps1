# 移动 my-gateway 的 Java 文件到新包结构（不删除）

Write-Host "=== Moving my-gateway Java files to new package ===" -ForegroundColor Cyan

# 源目录和目标目录
$sourceBase = "d:\source\my-gateway\src\main\java\com\example\gateway"
$targetBase = "d:\source\my-gateway\src\main\java\com\leoli\gateway"

# 检查源目录是否存在
if (-not (Test-Path $sourceBase)) {
    Write-Host "❌ Source directory not found: $sourceBase" -ForegroundColor Red
    exit 1
}

# 确保目标目录存在
New-Item -ItemType Directory -Force -Path $targetBase | Out-Null

# 复制所有子目录和文件（不删除源文件）
Write-Host "Copying files from $sourceBase to $targetBase..." -ForegroundColor Yellow
Get-ChildItem -Path $sourceBase -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Replace($sourceBase, '').TrimStart('\')
    $targetPath = Join-Path $targetBase $relativePath
    
    if ($_.PSIsContainer) {
        # 创建目录
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
            Write-Host "  Created dir: $relativePath" -ForegroundColor Gray
        }
    } else {
        # 复制文件（如果目标不存在）
        if (-not (Test-Path $targetPath)) {
            Copy-Item -Path $_.FullName -Destination $targetPath -Force
            Write-Host "  Copied: $relativePath" -ForegroundColor Gray
        }
    }
}

Write-Host "`n✅ Files copied successfully!" -ForegroundColor Green
Write-Host "   Source files are preserved at: $sourceBase" -ForegroundColor Gray
Write-Host "   New files created at: $targetBase" -ForegroundColor Gray
