@echo off
where gradle >nul 2>nul
if errorlevel 1 (
 echo Install Gradle 8.13 or run the Unix bootstrap using Git Bash.
 exit /b 1
)
gradle %*
