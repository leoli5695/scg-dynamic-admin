# Install Postman to D:\soft
# Postman uses Squirrel installer, we can specify install directory via LOCALAPPDATA override
$installDir = "D:\soft\Postman"
$installerPath = "D:\soft\Postman-Setup.exe"

# Create install directory
if (-not (Test-Path $installDir)) {
    New-Item -Path $installDir -ItemType Directory -Force | Out-Null
}

Write-Host "Installing Postman to $installDir..."
Write-Host "This may take a minute..."

# Squirrel-based installers respect the --install-to parameter or we can set LOCALAPPDATA
$env:LOCALAPPDATA = "D:\soft"
Start-Process -FilePath $installerPath -ArgumentList "/S", "/D=$installDir" -Wait -NoNewWindow -ErrorAction SilentlyContinue

# Wait for install to complete
Start-Sleep -Seconds 10

# Check if installed
$postmanExe = Get-ChildItem -Path "D:\soft" -Filter "Postman.exe" -Recurse -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($postmanExe) {
    Write-Host "Postman installed successfully!"
    Write-Host "Location: $($postmanExe.FullName)"
    # Launch Postman
    Start-Process -FilePath $postmanExe.FullName
    Write-Host "Postman launched!"
} else {
    Write-Host "Checking if Squirrel installed to D:\soft\Postman..."
    Get-ChildItem -Path "D:\soft" -Recurse -Depth 2 | Select-Object FullName
}
