# 批量替换 package 声明
$oldPackage = "package com.example.gateway.admin"
$newPackage = "package com.leoli.gateway.admin"

$oldImport = "import com.example.gateway.admin"
$newImport = "import com.leoli.gateway.admin"

$files = Get-ChildItem -Path "d:\source\gateway-admin\src\main\java\com\example\gateway\admin" -Recurse -Filter *.java

foreach ($file in $files) {
    $content = Get-Content $file.FullName -Raw -Encoding UTF8
    
    # 替换 package 声明
    $content = $content -replace [regex]::Escape($oldPackage), $newPackage
    
    # 替换 import 声明
    $content = $content -replace [regex]::Escape($oldImport), $newImport
    
    # 写回文件
    Set-Content -Path $file.FullName -Value $content -Encoding UTF8 -NoNewline
    
    Write-Host "Updated: $($file.Name)"
}

Write-Host "`n✅ Package refactoring completed! Updated $($files.Count) files."
