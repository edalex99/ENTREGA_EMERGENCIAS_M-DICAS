$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force -Path 'out/main' | Out-Null
$fuentes = @(Get-ChildItem 'src/main/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 17 -encoding UTF-8 -d 'out/main' $fuentes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp 'out/main' edu.iudigital.emergencias.Main @args
exit $LASTEXITCODE
