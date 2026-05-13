# Kill Postman
Get-Process *Postman* -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

# Find Postman install location
$localAppData = [Environment]::GetFolderPath('LocalApplicationData')
$postmanDir = Join-Path $localAppData "Postman"

if (Test-Path $postmanDir) {
    $updateExe = Join-Path $postmanDir "Update.exe"
    if (Test-Path $updateExe) {
        Write-Host "Found Update.exe, uninstalling..."
        Start-Process -FilePath $updateExe -ArgumentList "--uninstall" -Wait
        Write-Host "Uninstall command executed"
    }
    # Remove remaining files
    Start-Sleep -Seconds 3
    Remove-Item -Path $postmanDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Removed $postmanDir"
} else {
    Write-Host "Postman dir not found at $postmanDir"
}

# Also clean up AppData\Roaming\Postman
$roamingPostman = Join-Path ([Environment]::GetFolderPath('ApplicationData')) "Postman"
if (Test-Path $roamingPostman) {
    Remove-Item -Path $roamingPostman -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Removed $roamingPostman"
}

Write-Host "Uninstall complete!"
