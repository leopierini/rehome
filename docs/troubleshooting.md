# Troubleshooting

- [The installer can't find the TV](#the-installer-cant-find-the-tv)
- [Port 5555 is closed](#port-5555-is-closed)
- [Allow USB debugging?](#allow-usb-debugging)
- [Play Protect blocked the Wi-Fi helper](#play-protect-blocked-the-wi-fi-helper)
- [Home is the stock launcher again, or Android asks which Home to use](#home-is-the-stock-launcher-again-or-android-asks-which-home-to-use)
- [The installer stopped halfway](#the-installer-stopped-halfway)
- [Something on Home doesn't work](#something-on-home-doesnt-work)
- [Undo everything](#undo-everything)
- [Turn debugging off when you're done](#turn-debugging-off-when-youre-done)

## The installer can't find the TV

- The computer and the TV must be on the same network: the same hotspot, or the same router.
- The TV must be on, not in standby.
- Give the address yourself: Settings › Network & Internet on the TV shows it. Then `./install.sh --host <tv-ip>`.
- The scan only covers the network of the computer's main connection. With a VPN or several network interfaces, use
  `--host`.

## Port 5555 is closed

The TV answers but refuses the connection, or the scan finds nothing: debugging isn't listening.

- Check Settings › Device Preferences › Developer options › **USB debugging** is On. On these TVs it opens debugging
  over the network too.
- If it is On and the port stays closed, switch it **off and on again**. That usually opens it.
- No Developer options? Settings › Device Preferences › About, press OK on **Build** seven times.

## Allow USB debugging?

The first time a computer connects, the TV asks **"Allow USB debugging?"** with the computer's key fingerprint.
Choose **Allow** (tick "Always allow from this computer" to skip the question next time). The installer waits up to a
minute for it.

- If `adb` says "unauthorized" or "failed to authenticate", look at the TV: the question is waiting there.
- If the question never shows, revoke and start over: Developer options › **Revoke USB debugging authorizations**,
  switch USB debugging off and on, then connect again.

## Play Protect blocked the Wi-Fi helper

During the install, Play Protect can stop the Wi-Fi helper with "App blocked", because it targets an old Android
version. That is on purpose: on Android 11 only such an app can turn Wi-Fi on and join a saved network
([why](ux.md#why-there-is-a-wi-fi-helper-app)).

- Press **Install anyway** with the remote while the installer waits.
- If it was blocked, run `./install.sh` again and press Install anyway this time. A second run skips the packages
  already disabled, installs both apps again over themselves, grants the permissions and confirms rehome as Home.
- Without the helper, rehome still works: the Wi-Fi dropdown opens the TV's own Wi-Fi screens, and the Wi-Fi priority
  page says the helper is missing.

## Home is the stock launcher again, or Android asks which Home to use

A firmware update, or sometimes an app update, can enable the stock launcher again. Run
`python3 tools/rehome.py check`, then `python3 tools/rehome.py check --fix` ([details](firmware-update.md)).

If Android shows a list of Home apps, choose **rehome** and **Always**.

To go back to the stock launcher on purpose, use [restore](#undo-everything). Launcher settings › Choose default home
opens Android's Home picker too, but the stock launcher has to be enabled first, and only `restore` does that.

## The installer stopped halfway

The installer prints what it did and stops on the first thing that goes wrong; nothing is left half-applied without
a message.

- **Before its confirmation question** nothing on the TV has changed (only a backup was saved on the computer).
- **If rehome didn't start, didn't stay on screen for a few seconds or didn't become Home,** the stock launcher was
  kept, or enabled again, and made Home.
- **If the connection dropped during the Home handover,** the message gives the exact `adb` commands to enable the
  stock launcher again and make it Home. Reconnect and run them.
- In every case, `python3 tools/rehome.py restore` takes the TV back to its first backup.

## Something on Home doesn't work

- **The weather says "Set your city":** pick one in Launcher settings › Weather city. "No connection" there means the
  TV is offline, or the hotspot shows a sign-in page.
- **The player never appears:** something has to play for 10 seconds first. If it still doesn't, the launcher may
  have lost notification access (for example after you reinstalled it by hand): run `./install.sh` again, which grants
  it.
- **The data counter shows only "Since last restart":** the usage-stats permission is missing. Run `./install.sh`
  again.
- **The Wi-Fi pill shows no network name:** the location permission is missing (Android needs it to read the name).
  Run `./install.sh` again.
- **Scrolling stutters:** after 50 Hz content such as live TV the panel can stay at 50/100 Hz, where every launcher
  stutters. Open any streaming app for a moment; that usually brings 60 Hz back.
- **A black screen with a moving Philips logo:** the TV's screensaver, after about 6 minutes without a key on the
  remote ([OLED care](oled.md)). Press any key.
- **A red "Debugging on" pill:** network debugging is on. [Turn it off](#turn-debugging-off-when-youre-done) when
  you're done.

## Undo everything

```sh
python3 tools/rehome.py restore              # enable what rehome disabled, give Home back, reset the animation scales
python3 tools/rehome.py restore --uninstall  # the same, then remove rehome and its Wi-Fi helper
```

`restore` reads the earliest backup of that TV in `~/.rehome/backups/` and enables every package that is disabled now
but wasn't then, the stock launcher last, as long as it was on the TV at the time of the backup or rehome disabled it
(`check --fix` records what it disables, too). So it also enables apps that were on the TV before the install and
that you disabled by hand afterwards. An app installed after that backup and then disabled, by you or anyone else,
stays disabled: enable it in Settings › Apps if you want it back. `restore` gives Home back to the launcher the
backup recorded, puts the animation scales back, and uninstalls rehome only with `--uninstall` (and never while
rehome is still Home).
Don't delete `~/.rehome/backups/`.

## Turn debugging off when you're done

While USB debugging is on, the TV's debugging port (5555) accepts connections from any device on the same network,
and on a phone hotspot that's everyone connected to it. A new computer still has to be accepted on the TV's screen,
but there's no reason to leave the door open. rehome doesn't need debugging for daily use: only to install, update,
fix or restore.

With the remote:

1. Settings › Device Preferences › **Developer options** (OK on the red **Debugging on** pill opens it too).
2. **USB debugging: Off.** If there's also a "Wireless debugging" or network debugging switch, turn it off as well.
3. **Revoke USB debugging authorizations** › OK.
4. Restart the TV from its menu (Settings › Device Preferences › About › Restart), not at the wall.

Check from the computer, with the TV on and on the same network: `adb connect <tv-ip>:5555` should fail with
"Connection refused" (if it times out instead, the TV is asleep or the address is wrong). The red pill is gone from
Home, and Inputs gets its label back.

To work on the TV again later, turn USB debugging on and accept the computer's key on the TV.
