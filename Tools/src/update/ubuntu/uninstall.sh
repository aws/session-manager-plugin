#!bin/bash

echo "Uninstalling deb pkg"

# echo "Checking if the ssmcli is installed"
# uninstall the ssmcli if it is present
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
        else
            echo "-> ssmcli is not running"
        fi
        # echo "Uninstalling the ssmcli"
        dpkg -r ssmcli
        sleep 1
    else
        echo "-> ssmcli is not installed on this machine"
    fi
elif [[ "$(cat /proc/1/comm)" == "systemd" ]]; then
	if [[ "$(systemctl is-active ssmcli)" == "active" ]]; then
		# echo "-> ssmcli is running in the instance"
		# echo "Stopping the ssmcli"
		systemctl stop ssmcli
		# echo "ssmcli stopped"
		systemctl daemon-reload
		# echo "Reload daemon"
	else
		echo "-> ssmcli is not running in the instance"
	fi

	# echo "Uninstalling ssmcli"
	dpkg -r ssmcli

else
    echo "ssmcli is not installed on this machine"
fi