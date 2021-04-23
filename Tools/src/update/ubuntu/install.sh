#!/bin/bash

echo "Installing ubuntu pkg"

if [[ "$(cat /proc/1/comm)" == "init" ]]; then
    if [ "$(dpkg -s ssmcli | grep 'Status:')" == "Status: install ok installed" ]; then
	    # echo "-> ssmcli is installed in this instance"
	    # stop the ssmcli if it is running
	    # echo "Checking if the ssmcli is running"
	    if [ "$(status ssmcli)" != "ssmcli stop/waiting" ]; then
		    # echo "-> ssmcli is running in the instance"
  		    # echo "Stopping the ssmcli"
  		    /sbin/stop ssmcli
  		    sleep 1
	    fi
    fi

    # echo "Installing ssmcli"
    dpkg -i ssmcli.deb


    # echo "Starting ssmcli"
    /sbin/start ssmcli
    # echo "Status"
    status ssmcli

elif [[ "$(cat /proc/1/comm)" == "systemd" ]]; then
	if [[ "$(systemctl is-active ssmcli)" == "active" ]]; then
		# echo "-> ssmcli is running in the instance"
		systemctl stop ssmcli
		# echo "ssmcli stopped"
		systemctl daemon-reload
		# echo "Reload daemon"
	else
		echo "-> ssmcli is not running in the instance"
	fi

	# echo "Installing ssmcli"
	dpkg -i ssmcli.deb

	# echo "Starting ssmcli"
	systemctl daemon-reload
	systemctl start ssmcli
	systemctl status ssmcli
else

    echo "ssmcli is not installed on this machine"
fi