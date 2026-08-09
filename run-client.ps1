$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# 该机器 JDK21 cacerts 不完整，为 Gradle wrapper / daemon / 游戏客户端统一指定完整 truststore
$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStore=$root\gradle\truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=$root\gradle\truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
Push-Location $root
try { & .\gradlew.bat :fabric:runClient @args; exit $LASTEXITCODE } finally { Pop-Location }