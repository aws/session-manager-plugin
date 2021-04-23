Name         : ssmcli
Version      : %rpmversion
Release      : 1
Summary      : Manages shell experience using SSM APIs

Group        : Amazon/Tools
License      : Apache License, Version 2.0
URL          : http://docs.aws.amazon.com/ssm/latest/APIReference/Welcome.html

Packager     : Amazon.com, Inc. <http://aws.amazon.com>
Vendor       : Amazon.com

%description
This package provides Amazon SSM CLI for managing shell experience using SSM APIs

%files
%defattr(-,root,root,-)
/etc/amazon/ssmcli/seelog.xml.template
/usr/bin/ssmcli
/var/lib/amazon/ssmcli/

%config(noreplace) /etc/init/ssmcli.conf
%config(noreplace) /etc/systemd/system/ssmcli.service

# The scriptlets in %pre and %post are run before and after a package is installed.
# The scriptlets %preun and %postun are run before and after a package is uninstalled.
# The scriptlets %pretrans and %posttrans are run at start and end of a transaction.

# Examples for the scriptlets are run for clean install, uninstall and upgrade

# Clean install: %posttrans
# Uninstall:     %preun
# Upgrade:       %pre, %posttrans

%pre
# Stop the cli before the upgrade
if [ $1 -ge 2 ]; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/stop ssmcli
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl stop ssmcli
        systemctl daemon-reload
    fi
    rm stdout.txt
fi

%preun
# Stop the cli after uninstall
if [ $1 -eq 0 ] ; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/stop ssmcli
        sleep 1
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl stop ssmcli
        systemctl disable ssmcli
        systemctl daemon-reload
    fi
    rm stdout.txt
fi

%posttrans
# Start the cli after initial install or upgrade
if [ $1 -ge 0 ]; then
    /sbin/init --version &> stdout.txt
    if [[ `cat stdout.txt` =~ upstart ]]; then
        /sbin/start ssmcli
    elif [[ `systemctl` =~ -\.mount ]]; then
        systemctl enable ssmcli
        systemctl start ssmcli
        systemctl daemon-reload
    fi
    rm stdout.txt
fi

%clean
# rpmbuild deletes $buildroot after building, specifying clean section to make sure it is not deleted
