Name         : session-manager-plugin
Version      : %rpmversion
Release      : 1
Summary      : Manages shell experience using SSM APIs

Group        : Amazon/Tools
License      : Apache License, Version 2.0
URL          : http://docs.aws.amazon.com/ssm/latest/APIReference/Welcome.html

Packager     : Amazon.com, Inc. <http://aws.amazon.com>
Vendor       : Amazon.com

%description
This package provides Amazon SSM SessionManager for managing shell experience using SSM APIs

%files
%defattr(-,root,root,-)
/usr/local/sessionmanagerplugin/seelog.xml.template
/usr/local/sessionmanagerplugin/bin/session-manager-plugin
/var/lib/amazon/sessionmanagerplugin/
/usr/local/sessionmanagerplugin/LICENSE
/usr/local/sessionmanagerplugin/NOTICE
/usr/local/sessionmanagerplugin/README.md
/usr/local/sessionmanagerplugin/RELEASENOTES.md
/usr/local/sessionmanagerplugin/THIRD-PARTY
/usr/local/sessionmanagerplugin/VERSION

%config(noreplace) /etc/init/session-manager-plugin.conf
%config(noreplace) /etc/systemd/system/session-manager-plugin.service

# The scriptlet %postun runs after a package is uninstalled.
# The scriptlet %posttrans runs at the end of a transaction.

%posttrans
# Create symbolic link for plugin
ln -s /usr/local/sessionmanagerplugin/bin/session-manager-plugin /usr/local/bin/session-manager-plugin

%postun
rm /usr/local/bin/session-manager-plugin

%clean
# rpmbuild deletes $buildroot after building, specifying clean section to make sure it is not deleted