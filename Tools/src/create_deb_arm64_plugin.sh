#!/usr/bin/env bash
echo "***********************************************"
echo "Creating deb file for Ubuntu Linux arm64 Plugin"
echo "***********************************************"

rm -rf ${GO_SPACE}/bin/debian_arm64/debian

echo "Creating debian folders"

mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/bin/
mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/etc/init/
mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/
mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/usr/share/lintian/overrides/
mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/var/lib/amazon/sessionmanagerplugin/
mkdir -p ${GO_SPACE}/bin/debian_arm64/debian/lib/systemd/system/

echo "Copying application files"

cp ${GO_SPACE}/bin/linux_arm64_plugin/session-manager-plugin ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/bin/
cd ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/bin/; strip --strip-unneeded session-manager-plugin; cd ~-
cp ${GO_SPACE}/seelog_unix.xml ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/seelog.xml.template
cp ${GO_SPACE}/packaging/ubuntu_plugin/session-manager-plugin.conf ${GO_SPACE}/bin/debian_arm64/debian/etc/init/
cp ${GO_SPACE}/packaging/ubuntu_plugin/session-manager-plugin.service ${GO_SPACE}/bin/debian_arm64/debian/lib/systemd/system/

echo "Copying debian package config files"

cp ${GO_SPACE}/LICENSE ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/LICENSE
cp ${GO_SPACE}/NOTICE ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/NOTICE
cp ${GO_SPACE}/README.md ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/README.md
cp ${GO_SPACE}/RELEASENOTES.md ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/RELEASENOTES.md
cp ${GO_SPACE}/THIRD-PARTY ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/THIRD-PARTY
cp ${GO_SPACE}/VERSION ${GO_SPACE}/bin/debian_arm64/debian/usr/local/sessionmanagerplugin/VERSION
cp ${GO_SPACE}/packaging/ubuntu_plugin/conffiles ${GO_SPACE}/bin/debian_arm64/debian/
cp ${GO_SPACE}/packaging/ubuntu_plugin/preinst ${GO_SPACE}/bin/debian_arm64/debian/
cp ${GO_SPACE}/packaging/ubuntu_plugin/postinst ${GO_SPACE}/bin/debian_arm64/debian/
cp ${GO_SPACE}/packaging/ubuntu_plugin/prerm ${GO_SPACE}/bin/debian_arm64/debian/
cp ${GO_SPACE}/packaging/ubuntu_plugin/postrm ${GO_SPACE}/bin/debian_arm64/debian/
cp ${GO_SPACE}/packaging/ubuntu_plugin/lintian-overrides ${GO_SPACE}/bin/debian_arm64/debian/usr/share/lintian/overrides/sessionmanagerplugin

echo "Constructing the control file"

echo 'Package: session-manager-plugin' > ${GO_SPACE}/bin/debian_arm64/debian/control
echo 'Architecture: arm64' >> ${GO_SPACE}/bin/debian_arm64/debian/control
echo -n 'Version: ' >> ${GO_SPACE}/bin/debian_arm64/debian/control
cat ${GO_SPACE}/VERSION | tr -d "\n" >> ${GO_SPACE}/bin/debian_arm64/debian/control
echo '-1' >> ${GO_SPACE}/bin/debian_arm64/debian/control
cat ${GO_SPACE}/packaging/ubuntu_plugin/control >> ${GO_SPACE}/bin/debian_arm64/debian/control

echo "Constructing the changelog file"

echo -n 'session-manager-plugin (' > ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/changelog
cat VERSION | tr -d "\n"  >> ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/changelog
echo '-1) unstable; urgency=low' >> ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/changelog
cat ${GO_SPACE}/packaging/ubuntu_plugin/changelog >> ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/changelog

cp ${GO_SPACE}/packaging/ubuntu_plugin/debian-binary ${GO_SPACE}/bin/debian_arm64/debian/

echo "Setting permissions as required by debian"

cd ${GO_SPACE}/bin/debian_arm64/; find ./debian -type d | xargs chmod 755; cd ~-

echo "Compressing changelog"

cd ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/; export GZIP=-9; tar cvzf changelog.gz changelog --owner=0 --group=0 ; cd ~-

rm ${GO_SPACE}/bin/debian_arm64/debian/usr/share/doc/sessionmanagerplugin/changelog

echo "Creating tar"

# the below permissioning is required by debian
cd ${GO_SPACE}/bin/debian_arm64/debian/; tar czf data.tar.gz usr etc lib --owner=0 --group=0 ; cd ~-
cd ${GO_SPACE}/bin/debian_arm64/debian/; tar czf control.tar.gz control conffiles preinst postinst prerm postrm --owner=0 --group=0 ; cd ~-

echo "Constructing the deb package"

ar r ${GO_SPACE}/bin/debian_arm64/session-manager-plugin-`cat ${GO_SPACE}/VERSION`-1.deb ${GO_SPACE}/bin/debian_arm64/debian/debian-binary
ar r ${GO_SPACE}/bin/debian_arm64/session-manager-plugin-`cat ${GO_SPACE}/VERSION`-1.deb ${GO_SPACE}/bin/debian_arm64/debian/control.tar.gz
ar r ${GO_SPACE}/bin/debian_arm64/session-manager-plugin-`cat ${GO_SPACE}/VERSION`-1.deb ${GO_SPACE}/bin/debian_arm64/debian/data.tar.gz
cp ${GO_SPACE}/bin/debian_arm64/session-manager-plugin-`cat ${GO_SPACE}/VERSION`-1.deb ${GO_SPACE}/bin/debian_arm64/session-manager-plugin.deb
mv ${GO_SPACE}/bin/debian_arm64/session-manager-plugin-`cat ${GO_SPACE}/VERSION`-1.deb ${GO_SPACE}/bin/.

echo "Copying install and uninstall script to bin"

cp ${GO_SPACE}/Tools/src/update/ubuntu/install.sh ${GO_SPACE}/bin/debian_arm64/
cp ${GO_SPACE}/Tools/src/update/ubuntu/uninstall.sh ${GO_SPACE}/bin/debian_arm64/

chmod 755 ${GO_SPACE}/bin/debian_arm64/install.sh ${GO_SPACE}/bin/debian_arm64/uninstall.sh

echo "Zip deb, install and uninstall files"

tar -zcvf ${GO_SPACE}/bin/updates/sessionmanagerplugin/`cat ${GO_SPACE}/VERSION`/session-manager-plugin-ubuntu-arm64.tar.gz  -C ${GO_SPACE}/bin/debian_arm64/ session-manager-plugin.deb install.sh uninstall.sh

rm ${GO_SPACE}/bin/debian_arm64/install.sh
rm ${GO_SPACE}/bin/debian_arm64/uninstall.sh
