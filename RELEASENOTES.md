Latest
================
- Change data streaming related logs from debug to trace level.
- Fix typo for log config file.
- Fix interactive command session abruptly terminated issue.

1.2.205.0
================
- Introduce client timeout for session start request.
- Add support for signed session-manager-plugin.pkg file for macOS.

1.2.54.0
================
- Enhancement: Added support  for running session in NonInteractiveCommands execution mode.

1.2.30.0
================
- Bug Fix: (Port forwarding sessions only) Using system tmp folder for unix socket path.

1.2.7.0
================
- Enhancement: (Port forwarding sessions only) Reduced latency and improved overall performance.

1.1.61.0
================
- Enhancement: Added ARM support for Linux and Ubuntu.

1.1.54.0
================
- Bug Fix: Handle race condition scenario of packets being dropped when plugin is not ready.

1.1.50.0
================
- Enhancement: Add support for forwarding port session to local unix socket.

1.1.35.0
================
- Enhancement: For port forwarding session, send terminateSession flag to SSM agent on receiving Control-C signal.

1.1.33.0
================
- Enhancement: For port forwarding session, send disconnect flag to server when client drops tcp connection.

1.1.31.0
================
- Enhancement: Change to keep port forwarding session open until remote server closes the connection.

1.1.26.0
================
- Enhancement: Limit the rate of data transfer in port session.

1.1.23.0
================
- Enhancement: Add support for running SSH sessions using Session Manager.

1.1.17.0
================
- Enhancement: Add support for further encryption of session data using AWS KMS.

1.0.37.0
================
- Fix bug for Windows SessionManagerPlugin

1.0.0.0
================
- Initial SessionManagerPlugin release
