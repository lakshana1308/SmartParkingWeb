$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$files = Get-ChildItem .\src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force target\classes | Out-Null
javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d target\classes -cp "sqlite-jdbc-3.53.4.0.jar" $files
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed." }
java --add-modules jdk.httpserver -cp "target\classes;sqlite-jdbc-3.53.4.0.jar" SmartParkingWeb
