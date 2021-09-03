#!/usr/bin/env bash
set -euo pipefail
echo "*************************************************"
echo "Creating rpm file for Amazon Linux and RHEL amd64"
echo "*************************************************"

rm -rf ${GO_SPACE}/bin/linux_amd64/linux

echo "Creating rpmbuild workspace"

mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/SPECS
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/COORD_SOURCES
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/DATA_SOURCES
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/BUILD
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/RPMS
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/SRPMS
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/usr/bin/
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/etc/init/
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/etc/systemd/system/
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/etc/amazon/ssmcli/
mkdir -p ${GO_SPACE}/bin/linux_amd64/linux/var/lib/amazon/ssmcli/

echo "Copying application files"

cp ${GO_SPACE}/bin/linux_amd64/ssmcli ${GO_SPACE}/bin/linux_amd64/linux/usr/bin/
cp ${GO_SPACE}/seelog_unix.xml ${GO_SPACE}/bin/linux_amd64/linux/etc/amazon/ssmcli/seelog.xml.template
cp ${GO_SPACE}/packaging/linux/ssmcli.conf ${GO_SPACE}/bin/linux_amd64/linux/etc/init/
cp ${GO_SPACE}/packaging/linux/ssmcli.service ${GO_SPACE}/bin/linux_amd64/linux/etc/systemd/system/
cd ${GO_SPACE}/bin/linux_amd64/linux/usr/bin/; strip --strip-unneeded ssmcli; cd ~-

echo "Creating the rpm package"

SPEC_FILE="${GO_SPACE}/packaging/linux/ssmcli.spec"
BUILD_ROOT="${GO_SPACE}/bin/linux_amd64/linux"

setarch x86_64 rpmbuild -bb --define "rpmversion `cat ${GO_SPACE}/VERSION`" --define "_topdir bin/linux_amd64/linux/rpmbuild" --buildroot ${BUILD_ROOT} ${SPEC_FILE}

echo "Copying rpm files to bin"

cp ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/RPMS/x86_64/*.rpm ${GO_SPACE}/bin/
cp ${GO_SPACE}/bin/linux_amd64/linux/rpmbuild/RPMS/x86_64/*.rpm ${GO_SPACE}/bin/linux_amd64/ssmcli.rpm

echo "Copying install and uninstall script to bin"

cp ${GO_SPACE}/Tools/src/update/linux/install.sh ${GO_SPACE}/bin/linux_amd64/
cp ${GO_SPACE}/Tools/src/update/linux/uninstall.sh ${GO_SPACE}/bin/linux_amd64/

chmod 755 ${GO_SPACE}/bin/linux_amd64/install.sh ${GO_SPACE}/bin/linux_amd64/uninstall.sh

echo "Zip rpm, install and uninstall files"

tar -zcvf ${GO_SPACE}/bin/updates/ssmcli/`cat ${GO_SPACE}/VERSION`/ssmcli-linux-amd64.tar.gz  -C ${GO_SPACE}/bin/linux_amd64/ ssmcli.rpm install.sh uninstall.sh

rm ${GO_SPACE}/bin/linux_amd64/install.sh
rm ${GO_SPACE}/bin/linux_amd64/uninstall.sh