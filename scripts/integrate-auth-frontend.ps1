# 网关鉴权模块前端自动集成脚本 (PowerShell 版本)
# 自动将 Auth 配置表单和 JavaScript 添加到 index.html

Write-Host "🚀 Starting frontend integration for Auth module..." -ForegroundColor Green

# Define paths
$GatewayAdminDir = "d:/source/gateway-admin/src/main/resources/templates"
$IndexHtml = "$GatewayAdminDir/index.html"
$AuthFormTemplate = "$GatewayAdminDir/index-auth-form.html"
$AuthJsTemplate = "$GatewayAdminDir/index-auth-js.html"

# Check if files exist
if (-not (Test-Path $IndexHtml)) {
    Write-Host "❌ Error: index.html not found at $IndexHtml" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $AuthFormTemplate)) {
    Write-Host "❌ Error: index-auth-form.html not found" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $AuthJsTemplate)) {
    Write-Host "❌ Error: index-auth-js.html not found" -ForegroundColor Red
    exit 1
}

Write-Host "✅ All required files found" -ForegroundColor Green

# Create backup
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupFile = "$IndexHtml.backup.$Timestamp"
Copy-Item $IndexHtml $BackupFile
Write-Host "✅ Backup created: $BackupFile" -ForegroundColor Green

# Read template content
$AuthFormContent = Get-Content $AuthFormTemplate -Raw
$AuthJsContent = Get-Content $AuthJsTemplate -Raw

# Read current index.html
$IndexContent = Get-Content $IndexHtml -Raw

# Find insertion point for HTML (before "</div><!-- End of Plugins Panel -->")
$HtmlPattern = "<!-- End of Plugins Panel -->"
if ($IndexContent -match [regex]::Escape($HtmlPattern)) {
    Write-Host "✅ Found insertion point for HTML" -ForegroundColor Green
    
    # Insert HTML form before the pattern
    $NewContent = $IndexContent -replace [regex]::Escape($HtmlPattern), "$AuthFormContent`n$HtmlPattern"
    
    Write-Host "✅ HTML form inserted successfully" -ForegroundColor Green
} else {
    Write-Host "⚠️  Could not find '$HtmlPattern'" -ForegroundColor Yellow
    Write-Host "Please manually add the auth form HTML to index.html" -ForegroundColor Yellow
    $NewContent = $IndexContent
}

# Find insertion point for JS (before </script>)
$ScriptPattern = "</script>"
if ($NewContent -match [regex]::Escape($ScriptPattern)) {
    Write-Host "✅ Found insertion point for JavaScript" -ForegroundColor Green
    
    # Insert JS before the pattern
    $FinalContent = $NewContent -replace [regex]::Escape($ScriptPattern), "$AuthJsContent`n$ScriptPattern"
    
    Write-Host "✅ JavaScript inserted successfully" -ForegroundColor Green
} else {
    Write-Host "❌ Could not find closing </script> tag" -ForegroundColor Red
    Write-Host "Please manually add the JavaScript code to index.html" -ForegroundColor Yellow
    $FinalContent = $NewContent
}

# Save the modified file
Set-Content -Path $IndexHtml -Value $FinalContent -Encoding UTF8 -NoNewline
Write-Host "✅ index.html updated successfully" -ForegroundColor Green

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "✅ Frontend integration completed!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Backup saved as: $BackupFile" -ForegroundColor Yellow
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "1. Open $IndexHtml in your editor"
Write-Host "2. Verify the HTML form was added correctly"
Write-Host "3. Verify the JavaScript was added correctly"
Write-Host "4. Test the auth configuration UI"
Write-Host ""
Write-Host "If you encounter any issues, restore from backup:" -ForegroundColor Yellow
Write-Host "  Copy-Item $BackupFile $IndexHtml"
Write-Host ""
