$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force -Path 'out/main', 'out/test' | Out-Null
$fuentes = @(Get-ChildItem 'src/main/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 17 -encoding UTF-8 -d 'out/main' $fuentes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$pruebas = @(Get-ChildItem 'src/test/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 17 -encoding UTF-8 -cp 'out/main' -d 'out/test' $pruebas
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea -cp 'out/main;out/test' edu.iudigital.emergencias.ProjectTests
exit $LASTEXITCODE
