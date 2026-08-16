@echo off
REM Command-center UI for Windows. Requires JDK 21 and Python 3.
cd /d "%~dp0\.."
if not exist "python\.venv\Scripts\python.exe" (
  py -3 -m venv python\.venv
  python\.venv\Scripts\python.exe -m pip install -r python\requirements.txt
)
set SALUTE_MESH_ROOT=%CD%
set SALUTE_PYTHON=%CD%\python\.venv\Scripts\python.exe
call gradlew.bat :desktop:run
