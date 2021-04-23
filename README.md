
# Session Manager Plugin

This plugin helps you to use the AWS Command Line Interface (AWS CLI) to start and end sessions to your managed instances. Session Manager is a capability of AWS Systems Manager.

## Overview

Session Manager is a fully managed AWS Systems Manager capability that lets you manage your Amazon Elastic Compute Cloud (Amazon EC2) instances, on-premises instances and virtual machines. Session Manager provides secure and auditable instance management without the need to open inbound ports. When you use the Session Manager plugin with the AWS CLI to start a session, the plugin builds the websocket connection to your managed instances.

### Prerequisites

Before using Session Manager, make sure your environment meets the following requirements. [Complete Session Manager prerequisites](http://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-prerequisites.html).

### Starting a session

For information about starting a session using the AWS CLI, see [Starting a session (AWS CLI)](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-sessions-start.html#sessions-start-cli).

### Troubleshooting

For information about troubleshooting, see [Troubleshooting Session Manager](http://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-troubleshooting.html).


### Working with Docker

To build the Session Manager plugin in a `Docker` container, complete the following steps:

1. Install [`docker`](https://docs.docker.com/engine/install/centos/)

2. Build the `docker` image
```
docker build -t session-manager-plugin-image .
```
3. Build the plugin
```
docker run -it --rm --name session-manager-plugin -v `pwd`:/session-manager-plugin session-manager-plugin-image make release
```

### Working with Linux

To build the binaries required to install the Session Manager plugin, complete the following steps.

1. Install `golang`

2. Install `rpm-build` and `rpmdevtools`

3. Install `gcc 8.3+` and `glibc 2.27+`

4. Run `make release` to build the plugin for Linux, Debian, macOS and Windows.

5. Change to the directory of your local machine's operating system architecture and open the `session-manager-plugin` directory. Then follow the installation procedure that applies to your local machine. For more information, see [Install the Session Manager plugin for the AWS CLI](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html). If the machine you're building the plugin on differs from the machine you plan to install the plugin on you will need to copy the `session-manager-plugin` binary to the appropriate directory for that operating system.

```
Linux - /usr/local/sessionmanagerplugin/bin/session-manager-plugin

macOS - /usr/local/sessionmanagerplugin/bin/session-manager-plugin

Windows - C:\Program Files\Amazon\SessionManagerPlugin\bin\session-manager-plugin.exe
```

The `ssmcli` binary is available for some operating systems for testing purposes only. The following is an example command using this binary.

```
./ssmcli start-session --instance-id i-1234567890abcdef0 --region us-east-2
```

### Directory structure

Source code

* `sessionmanagerplugin/session` contains the source code for core functionalities
* `communicator/` contains the source code for websocket related operations
* `vendor/src` contains the vendor package source code
* `packaging/` contains rpm and dpkg artifacts
* `Tools/src` contains build scripts

## Feedback

Thank you for helping us to improve the Session Manager plugin. Please send your questions or comments to the [Systems Manager Forum](https://forums.aws.amazon.com/forum.jspa?forumID=185&start=0)

## License

The session-manager-plugin is licensed under the Apache 2.0 License.
