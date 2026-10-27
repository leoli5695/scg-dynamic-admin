param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand,
    [string]$NodeCommand = 'node',
    [string]$Levels = '1,16,64',
    [int]$Repetitions = 2,
    [int]$WarmupMs = 3000,
    [int]$HoldMs = 8000,
    [string]$Cases,
    [ValidateSet('true','false')][string]$BasicCoalescing = 'true',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $JavaHome -and (Test-Path 'D:/soft/IntelliJ IDEA 2026.2.3/jbr/bin/java.exe')) {
    $JavaHome = 'D:/soft/IntelliJ IDEA 2026.2.3/jbr'
}
if (-not $JavaHome) { throw 'Set -JavaHome to a JDK 21+ installation.' }
if (-not $MavenCommand) {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($maven) { $MavenCommand = $maven.Source }
    elseif (Test-Path 'D:/soft/IntelliJ IDEA 2026.2.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd') {
        $MavenCommand = 'D:/soft/IntelliJ IDEA 2026.2.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd'
    } else { throw 'Set -MavenCommand to mvn.cmd.' }
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot ('results/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$previousJavaHome = $env:JAVA_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    # This small test creates a reactor-aware classpath (including license-client/target/classes).
    & $MavenCommand -B -pl my-gateway -am test '-Dmaven.compiler.proc=full' '-Dtest=GatewayConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' *> (Join-Path $OutputDirectory 'build.log')
    if ($LASTEXITCODE -ne 0) { throw "Build failed. See $OutputDirectory/build.log" }
    [xml]$report = Get-Content -Raw 'my-gateway/target/surefire-reports/TEST-com.leoli.gateway.config.GatewayConfigurationTest.xml'
    $classpath = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    if (-not $classpath) { throw 'Surefire classpath missing.' }
    $classpathFile = Join-Path $OutputDirectory 'classpath.txt'
    [IO.File]::WriteAllText($classpathFile, $classpath, [Text.UTF8Encoding]::new($false))
    $caseArgs = @('--basic-coalescing', $BasicCoalescing)
    if ($Cases) { $caseArgs += @('--cases', $Cases) }
    & $NodeCommand (Join-Path $PSScriptRoot 'run.mjs') --java (Join-Path $JavaHome 'bin/java.exe') --classpath-file $classpathFile --out $OutputDirectory --levels $Levels --repetitions $Repetitions --warmup-ms $WarmupMs --hold-ms $HoldMs @caseArgs
    if ($LASTEXITCODE -ne 0) { throw "Baseline run failed. See $OutputDirectory" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
