@echo off
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all -d build\classes src\Mhc.java src\MhcTest.java
if errorlevel 1 exit /b 1
java -cp build\classes MhcTest
if errorlevel 1 exit /b 1
java -m jdk.jartool/sun.tools.jar.Main --create --file mhc.jar --main-class Mhc -C build\classes .
if errorlevel 1 exit /b 1
echo Built mhc.jar
