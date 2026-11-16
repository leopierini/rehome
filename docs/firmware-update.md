# After a firmware update

A Philips firmware update rewrites the system, and with it the list of disabled apps: the apps the cleanup turned
off come back, and the stock Google TV launcher can take Home back. rehome itself, its Wi-Fi helper and your settings
should stay, because they live with the user's apps and data, which an update normally keeps (expected, not yet
seen on this TV).

## Put the cleanup back

1. Turn debugging on again if it's off: Settings › Device Preferences › Developer options › **USB debugging: On**.
2. See what changed (this changes nothing):

   ```sh
   python3 tools/rehome.py check --host <tv-ip>
   ```

   It lists each package from the cleanup list that is enabled again, whether rehome is installed, and where Home
   goes.
3. Put it back:

   ```sh
   python3 tools/rehome.py check --fix --host <tv-ip>
   ```

   It saves a backup, disables those packages again and makes rehome Home before it disables the stock launcher,
   the same guarded handover as the install. What it disables is recorded with that backup, so `restore` undoes it
   too. The optional group (Rakuten TV, TED) is included only if an earlier install applied it,
   or with `--with-optional`.
4. Turn debugging off again ([steps](troubleshooting.md#turn-debugging-off-when-youre-done)).

Play-Fi and Mimi can switch themselves back on at a restart even without an update. `check --fix` handles them too,
and both are hidden from Home in the meantime.

**No computer at hand?** If the stock home screen is back, rehome is still installed: open it from the stock
launcher's app list, hold OK on the gear, then Launcher settings › **Choose default home** and pick rehome. This works
only if the firmware shows Android's Home picker; the cleaned-up apps stay enabled until you run `check --fix`.

## Automatic updates on a hotspot

Philips firmware updates are big, around 1–1.5 GB, and the TV downloads them over whatever network it's on, a phone
hotspot included.

- **Automatic updates on:** fixes arrive by themselves, but a download can eat a data plan without warning, and each
  update undoes the cleanup until you run `check --fix`.
- **Automatic updates off** (what I do): Settings › Update software › **Automatic software update: Off** (the exact
  name varies a little between firmware versions). Now and then, on a connection that can take the download, check by
  hand: Settings › Update software › Search for updates › Internet. Then run `check --fix`.

Philips also offers firmware files for a USB stick on its support site. Install one only if it is newer than the
version the TV already runs (Settings › Update software › Current software info); the file on the site can lag behind
the over-the-air release.

App updates from the Play Store are separate: on a network Android treats as metered, the Play Store doesn't update
apps by itself, so update them by hand now and then on a good connection. If an app update ever brings the stock home
screen back, `check --fix` puts rehome back the same way.
