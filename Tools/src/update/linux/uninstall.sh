#!/bin/bash

echo "Uninstalling ssmcli"

if [[ $(/sbin/init --version 2> /dev/null) =~ upstart ]]; then
	echo "Checking if the ssmcli is installed" 
	if [ "$(rpm -q ssmcli)" != "package ssmcli is not installed" ]; then
		echo "-> ssmcli is installed on this machine"
		echo "Uninstalling the ssmcli" 	
		rpm --erase ssmcli
		sleep 1
	else
		echo "-> ssmcli is not installed on this machine"
	fi
elif [[ $(systemctl 2> /dev/null) =~ -\.mount ]]; then
	echo "Checking if the ssmcli is installed" 
	if [[ "$(systemctl status ssmcli)" != *"Loaded: not-found"* ]]; then
		echo "-> ssmcli is installed on this machine"
		echo "Uninstalling the ssmcli" 	
		rpm --erase ssmcli
		sleep 1
	else
		echo "-> ssmcli is not installed on this machine"
	fi
else
	echo "ssmcli is not installed on this machine"
fi