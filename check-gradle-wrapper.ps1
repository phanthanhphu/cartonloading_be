$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$jar = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
$bat = Join-Path $root 'gradlew.bat'
Write-Host "Project: $root"
Write-Host "gradlew.bat exists: $(Test-Path $bat)"
Write-Host "wrapper jar exists: $(Test-Path $jar)"
if (Test-Path $bat) {
  Write-Host "\n--- gradlew.bat launcher lines ---"
  Get-Content $bat | Select-String -Pattern 'GradleWrapperMain','-jar','CLASSPATH'
}
if (Test-Path $jar) {
  Write-Host "\n--- wrapper jar main class check ---"
  $jarExe = Join-Path $env:JAVA_HOME 'bin\jar.exe'
  if (-not (Test-Path $jarExe)) { $jarExe = 'jar.exe' }
  & $jarExe tf $jar | Select-String 'org/gradle/wrapper/GradleWrapperMain.class'
}
