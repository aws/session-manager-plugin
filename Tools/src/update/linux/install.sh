#!/bin/bash

if [[ $(/sbin/init --version 2> /dev/null) =~ upstart ]]; then
	echo "upstart detected"
	echo "Installing ssmcli" 
	rpm -U ssmcli.rpm
	ssmcliVersion=$(rpm -q --qf '%{VERSION}\n' ssmcli)
	echo "Installed version: $ssmcliVersion"
	echo "starting ssmcli"
	/sbin/start ssmcli
	echo "$(status ssmcli)"
elif [[ $(systemctl 2> /dev/null) =~ -\.mount ]]; then
	if [[ "$(systemctl is-active ssmcli)" == "active" ]]; then
		echo "-> ssmcli is running"
		echo "Stopping the ssmcli"
		echo "$(systemctl stop ssmcli)"
		echo "ssmcli stopped"
		echo "$(systemctl daemon-reload)"
		echo "Reload daemon"	
	else
		echo "-> ssmcli is not running on the machine"
	fi
		
	echo "Installing ssmcli" 
	echo "$(rpm -U ssmcli.rpm)"

	echo "Starting ssmcli"
	$(systemctl daemon-reload)
	$(systemctl start ssmcli)
	echo "$(systemctl status ssmcli)"
else
	echo "ssmcli is not installed on this machine"
fi
