#!/usr/bin/env bash
echo "********************************************************"
echo "Creating rpm file for Amazon Linux and RHEL arm64 Plugin"
echo "********************************************************"

rm -rf ${GO_SPACE}/bin/linux_arm64_plugin/linux

echo "Creating rpmbuild workspace"

mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/SPECS
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/COORD_SOURCES
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/DATA_SOURCES
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/BUILD
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/RPMS
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/SRPMS
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/bin/
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/bin/
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/etc/init/
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/etc/systemd/system/
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/etc/amazon/sessionmanagerplugin/
mkdir -p ${GO_SPACE}/bin/linux_arm64_plugin/linux/var/lib/amazon/sessionmanagerplugin/

echo "Copying application files"

cp ${GO_SPACE}/LICENSE ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/LICENSE
cp ${GO_SPACE}/NOTICE ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/NOTICE
cp ${GO_SPACE}/README.md ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/README.md
cp ${GO_SPACE}/RELEASENOTES.md ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/RELEASENOTES.md
cp ${GO_SPACE}/THIRD-PARTY ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/THIRD-PARTY
cp ${GO_SPACE}/VERSION ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/VERSION
cp ${GO_SPACE}/bin/linux_arm64_plugin/session-manager-plugin ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/bin/
cp ${GO_SPACE}/seelog_unix.xml ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/seelog.xml.template
cp ${GO_SPACE}/packaging/linux_plugin/session-manager-plugin.conf ${GO_SPACE}/bin/linux_arm64_plugin/linux/etc/init/
cp ${GO_SPACE}/packaging/linux_plugin/session-manager-plugin.service ${GO_SPACE}/bin/linux_arm64_plugin/linux/etc/systemd/system/
cd ${GO_SPACE}/bin/linux_arm64_plugin/linux/usr/local/sessionmanagerplugin/bin/; strip --strip-unneeded session-manager-plugin; cd ~-

echo "Creating the rpm package"

SPEC_FILE="${GO_SPACE}/packaging/linux_plugin/session-manager-plugin.spec"
BUILD_ROOT="${GO_SPACE}/bin/linux_arm64_plugin/linux"

rpmbuild -bb --target aarch64 --define "rpmversion `cat ${GO_SPACE}/VERSION`" --define "_topdir bin/linux_arm64_plugin/linux/rpmbuild" --buildroot ${BUILD_ROOT} ${SPEC_FILE}

echo "Copying rpm files to bin"

cp ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/RPMS/aarch64/*.rpm ${GO_SPACE}/bin/
cp ${GO_SPACE}/bin/linux_arm64_plugin/linux/rpmbuild/RPMS/aarch64/*.rpm ${GO_SPACE}/bin/linux_arm64_plugin/session-manager-plugin.rpm

echo "Copying install and uninstall script to bin"

cp ${GO_SPACE}/Tools/src/update/linux/install.sh ${GO_SPACE}/bin/linux_arm64_plugin/
cp ${GO_SPACE}/Tools/src/update/linux/uninstall.sh ${GO_SPACE}/bin/linux_arm64_plugin/

chmod 755 ${GO_SPACE}/bin/linux_arm64_plugin/install.sh ${GO_SPACE}/bin/linux_arm64_plugin/uninstall.sh

echo "Zip rpm, install and uninstall files"

tar -zcvf ${GO_SPACE}/bin/updates/sessionmanagerplugin/`cat ${GO_SPACE}/VERSION`/session-manager-plugin-linux-arm64.tar.gz  -C ${GO_SPACE}/bin/linux_arm64_plugin/ session-manager-plugin.rpm install.sh uninstall.sh

rm ${GO_SPACE}/bin/linux_arm64_plugin/install.sh
rm ${GO_SPACE}/bin/linux_arm64_plugin/uninstall.sh