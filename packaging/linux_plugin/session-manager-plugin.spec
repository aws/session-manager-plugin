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

# The scriptlets in %pre and %post are run before and after a package is installed.
# The scriptlets %preun and %postun are run before and after a package is uninstalled.
# The scriptlets %pretrans and %posttrans are run at start and end of a transaction.

# Examples for the scriptlets are run for clean install, uninstall and upgrade

# Clean install: %posttrans
# Uninstall:     %preun
# Upgrade:       %pre, %posttrans

%pre
# Stop the plugin before the upgrade
if [ $1 -ge 2 ]; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/stop session-manager-plugin
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl stop session-manager-plugin
        systemctl daemon-reload
    fi
    rm stdout.txt
fi

%preun
# Stop the plugin after uninstall
if [ $1 -eq 0 ] ; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/stop session-manager-plugin
        sleep 1
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl stop session-manager-plugin
        systemctl disable session-manager-plugin
        systemctl daemon-reload
    fi
    rm stdout.txt
fi

%posttrans
# Start the plugin after initial install or upgrade
if [ $1 -ge 0 ]; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/start session-manager-plugin
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl enable session-manager-plugin
        systemctl start session-manager-plugin
        systemctl daemon-reload
    fi
    rm stdout.txt
fi
ln -s /usr/local/sessionmanagerplugin/bin/session-manager-plugin /usr/local/bin/session-manager-plugin

%postun
rm /usr/local/bin/session-manager-plugin

%clean
# rpmbuild deletes $buildroot after building, specifying clean section to make sure it is not deleted
