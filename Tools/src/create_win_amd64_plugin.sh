#!/usr/bin/env bash
set -euo pipefail
echo "****************************************"
echo "Creating zip file for Windows amd64 plugin"
echo "****************************************"

BIN_FOLDER=${GO_SPACE}/bin
BUILD_FOLDER=${BIN_FOLDER}/windows_amd64_plugin
PACKAGE_FOLDER=${BUILD_FOLDER}/windows

rm -rf ${PACKAGE_FOLDER}

echo "Creating windows folders"

mkdir -p ${PACKAGE_FOLDER}

echo "Copying application files"

mkdir -p ${PACKAGE_FOLDER}/bin
cp ${BUILD_FOLDER}/session-manager-plugin.exe ${PACKAGE_FOLDER}/bin/session-manager-plugin.exe
cp ${GO_SPACE}/seelog_windows.xml.template ${PACKAGE_FOLDER}/seelog.xml.template

echo "Copying windows package config files"

cp ${GO_SPACE}/LICENSE ${PACKAGE_FOLDER}/LICENSE
cp ${GO_SPACE}/NOTICE ${PACKAGE_FOLDER}/NOTICE
cp ${GO_SPACE}/README.md ${PACKAGE_FOLDER}/README.md
cp ${GO_SPACE}/RELEASENOTES.md ${PACKAGE_FOLDER}/RELEASENOTES.md
cp ${GO_SPACE}/THIRD-PARTY ${PACKAGE_FOLDER}/THIRD-PARTY

echo "Constructing the zip package"

if [ -f ${PACKAGE_FOLDER}/session-manager-plugin.zip ]
then
    rm ${PACKAGE_FOLDER}/session-manager-plugin.zip
fi
cd ${PACKAGE_FOLDER}
zip -r package *

cp ${GO_SPACE}/Tools/src/update/windows/install.bat ${PACKAGE_FOLDER}/
cp ${GO_SPACE}/Tools/src/update/windows/uninstall.bat ${PACKAGE_FOLDER}/

WINDOWS_AMD64_ZIP=${GO_SPACE}/bin/updates/sessionmanagerplugin/`cat ${GO_SPACE}/VERSION`/session-manager-plugin-windows-amd64.zip
zip -j ${WINDOWS_AMD64_ZIP} ${PACKAGE_FOLDER}/package.zip
zip -j ${WINDOWS_AMD64_ZIP} ${PACKAGE_FOLDER}/install.bat
zip -j ${WINDOWS_AMD64_ZIP} ${PACKAGE_FOLDER}/uninstall.bat

cp ${WINDOWS_AMD64_ZIP} ${BUILD_FOLDER}
rm -rf ${PACKAGE_FOLDER}