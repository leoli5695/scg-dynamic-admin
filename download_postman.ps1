# Create target directory
$installDir = "D:\soft"
if (-not (Test-Path $installDir)) {
    New-Item -Path $installDir -ItemType Directory -Force | Out-Null
    Write-Host "Created directory: $installDir"
}

# Download Postman
$downloadUrl = "https://dl.pstmn.io/download/latest/win64"
$installerPath = Join-Path $installDir "Postman-Setup.exe"

Write-Host "Downloading Postman (this may take a few minutes)..."
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$webClient = New-Object System.Net.WebClient
$webClient.DownloadFile($downloadUrl, $installerPath)

if (Test-Path $installerPath) {
    $fileSize = (Get-Item $installerPath).Length / 1MB
    Write-Host "Download complete! File size: $([math]::Round($fileSize, 1)) MB"
    Write-Host "File saved to: $installerPath"
} else {
    Write-Host "ERROR: Download failed!"
    exit 1
}
