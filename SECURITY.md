# Security

Please report a vulnerability privately, not in a public issue: use **Report a vulnerability** in this repository's
[Security tab](https://github.com/leopierini/rehome/security/advisories/new). Include the steps to reproduce it, the
rehome version, and the TV model and firmware. Leave out your IP address, Wi-Fi names and serial number.
If the Security tab has no such button, open an issue that asks for a private contact, with no details in it.

You'll get an answer as soon as possible, and a fix will be released before the details are made public.

Areas where a report matters most:

- **The installer** (`tools/rehome.py`): anything that makes it uninstall a package, disable one on its protected
  list, or run a command on the TV other than the ones it shows.
- **The Wi-Fi helper** (`io.github.leopierini.rehome.wifi`): its commands are guarded by a signature permission, so
  only apps signed with the same key can send them. A way around that matters.
- **Network traffic:** anything the launcher sends beyond what the README's Privacy section lists.
- **The test switches** (`rehome_test_*` global settings): they must do nothing while ADB is off.

## ADB on the network

Installing rehome needs USB debugging on, which on these TVs opens the debugging port (5555) to the whole network.
On a phone hotspot that means every device connected to it. A computer has to be accepted on the TV's screen the
first time, but a computer accepted once stays trusted until the authorizations are revoked. Turn debugging off and
revoke the authorizations when you're done
([steps](docs/troubleshooting.md#turn-debugging-off-when-youre-done)); rehome doesn't need it for daily use. While it's
on, Home shows a red **Debugging on** pill as a reminder.
