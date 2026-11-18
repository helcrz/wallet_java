param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Goals)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $jdk = Get-ChildItem -Path '.tools/jdk-*/bin/javac.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) {
        $env:JAVA_HOME = $jdk.Directory.Parent.FullName
        $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
    }
    $localMaven = Get-ChildItem -Path '.tools/apache-maven-*/bin/mvn.cmd' -ErrorAction SilentlyContinue | Select-Object -First 1
    $maven = if ($localMaven) { $localMaven.FullName } else { 'mvn' }
    if (!$Goals) { $Goals = @('javafx:run') }
    & $maven @Goals
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
