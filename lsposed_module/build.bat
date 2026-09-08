@echo off
REM Magic Window Adapter Build Script
REM 魔窗适配模块构建脚本

echo ========================================
echo Magic Window Adapter Build Script
echo ========================================
echo.

REM 检查 Java 版本
echo Checking Java version...
java -version

REM 检查 Gradle Wrapper
if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found!
    exit /b 1
)

REM 清理并构建 Release 版本
echo.
echo Building Release version...
call gradlew.bat clean assembleRelease

REM 输出构建结果
echo.
echo ========================================
echo Build completed!
echo ========================================
echo.
echo APK location:
dir /s /b app\build\outputs\apk\release\*.apk

echo.
echo Done!
pause
