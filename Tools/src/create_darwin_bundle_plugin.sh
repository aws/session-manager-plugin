#!/usr/bin/env bash
echo "**********************************************"
echo "Creating bundle zip file Mac OS X amd64 Plugin"
echo "**********************************************"

rm -rf ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle

echo "Creating bundle workspace"

mkdir -p ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/bin

echo "Copying application files"

cp ${GO_SPACE}/LICENSE ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/LICENSE
cp ${GO_SPACE}/NOTICE ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/NOTICE
cp ${GO_SPACE}/THIRD-PARTY ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/THIRD-PARTY
cp ${GO_SPACE}/README.md ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/README.md
cp ${GO_SPACE}/RELEASENOTES.md ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/RELEASENOTES.md
cp ${GO_SPACE}/VERSION ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/VERSION
cp ${GO_SPACE}/bin/darwin_amd64_plugin/session-manager-plugin ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/bin/session-manager-plugin
cp ${GO_SPACE}/seelog_unix.xml ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/seelog.xml.template

echo "Copying install script"

cp ${GO_SPACE}/Tools/src/darwin/install ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/install
chmod 755 ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/install;

cd ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle/bin/; strip --strip-unneeded session-manager-plugin; cd ~-

echo "Creating the bundle zip file"

if [ -f ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle.zip ]
then
    rm ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle.zip
fi

cd ${GO_SPACE}/bin/darwin_amd64_plugin;
zip -r ${GO_SPACE}/bin/darwin_amd64_plugin/sessionmanager-bundle.zip ./sessionmanager-bundle
