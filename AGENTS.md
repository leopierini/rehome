# AGENTS.md

Instructions for a coding assistant that helps someone install rehome on their TV. People can read the README instead; this file is the same procedure in
the form an assistant needs.

rehome is a home-screen launcher plus a cleanup for Philips Android TV 11 sets on the `PH1M_EA_9970A` board
(MediaTek MT5895, firmware `TPM211*`: 55/65OLED706, 55OLED707, 65OLED807, 65OLED907, 65PML9506, 50PUS8517; not
the 55OLED708, which has a different board). It has been tested on one 55OLED706.

## How to work

- **Use `tools/rehome.py`.** Its subcommands do every step and carry the safety checks. Do not write your own
  adb commands for the cleanup, the install or the Home handover, and do not run commands from other projects'
  guides on this TV.
- **Show the person each step and ask before each change.** Say what the next command will change on the TV
  and how it is undone, wait for a yes, then run it. Read-only commands (`--help`, `install --dry-run`, `check`
  without `--fix`, `backup`) need no confirmation, but say what you are running.
- **Pass `--yes` only after the person has said yes** to exactly what the command will do. Your shell has no
  terminal, so without `--yes` the tool refuses to change anything; that is on purpose. Never pass `--yes` to
  get past a question the person has not answered.
- **When the tool stops, stop.** Relay its message in plain words and ask what to do. Do not retry in a loop,
  work around a refusal, or edit the code to make it pass.

## The steps

1. **Tools on the computer.** Python 3.9+ and `adb` from Android platform-tools (`brew install
   android-platform-tools` on macOS, `sudo apt install adb` on Debian/Ubuntu). Windows: use WSL.
2. **ADB on the TV** (the person does this with the remote): Settings > Device Preferences > About > press OK
   on "Build" seven times; then Developer options > USB debugging: On. If port 5555 stays closed, switch it off
   and on again. Ask for the TV's IP address (Settings > Network & Internet), or let the tool scan the local
   network.
3. **Look first:** `python3 tools/rehome.py install --dry-run --host <tv ip>`. It connects (the person must
   accept "Allow USB debugging?" on the TV screen), reads the model and board, saves a backup of the TV's state
   in `~/.rehome/backups/`, and prints the cleanup plan: each package with its reason, and what it skips (not
   installed, already disabled, provides a TV input). Nothing on the TV changes.
   - If it says the board is unknown: tell the person the cleanup list is not for their TV. `--any-board`
     installs only the launcher, with no cleanup; offer it only if they want that.
4. **Go through the plan with the person**, group by group (`cleanup/ph1m_ea_9970a.txt`). To keep a package,
   comment out its line in that file. Ask about the two extras separately:
   - the optional group (`cleanup/optional-preloads.txt`: Rakuten TV, TED), `--with-optional`;
   - system animation scale 0.25 (snappier menus; restore undoes it), `--animations`.
5. **Apply**, once they agree: `python3 tools/rehome.py install --yes --host <tv ip>` plus the extras they chose.
   In order it disables the list with `pm disable-user --user 0`, installs the launcher and its Wi-Fi
   companion, grants the permissions their features need (location for both, TV listings, usage stats and the
   media listener for the launcher), makes rehome Home, checks that it is still on screen in the same process
   3 s later, and only then disables the stock launcher. If rehome falls over or Home does not end up on it,
   it puts the stock launcher back by itself.
   - Tell the person to watch the TV while the Wi-Fi companion installs: Play Protect may say "App blocked";
     they press "Install anyway" with the remote. If it was blocked, run the same install command
     again and have them press "Install anyway" this time. A second run skips the packages already
     disabled, installs both apps again over themselves, grants the permissions and confirms rehome as Home.
6. **Check:** `python3 tools/rehome.py check --host <tv ip>`; ask the person to press Home on the real remote
   and confirm rehome comes up. An injected key is not proof.
7. **Hand over the manual steps** the installer prints: switch an OLED off with the remote, not at the wall;
   wait about 15 s before unplugging (Android writes the disabled state late); and **close ADB**: Developer
   options > USB debugging: Off, "Revoke USB debugging authorizations", then restart the TV from its menu. The
   person does this with the remote; do not switch debugging off over ADB yourself. Until then port 5555 is
   open to anyone on the network.

Later:

- **After a firmware update** (which enables apps again and can give Home back to the stock launcher):
  `check`, then, with the person's yes, `check --fix --yes`.
- **To undo everything:** `restore` enables what rehome disabled (the difference from the earliest backup of
  that TV), gives Home back to the stock launcher and puts the animation scales back; `--uninstall` also removes
  the two rehome apps. Ask first, then `restore --yes [--uninstall]`.
- `backup` saves the current state again. It never overwrites an earlier backup, and `restore` always uses the
  earliest one.

## Safety rules (not negotiable)

- **Disable, never uninstall.** No `pm uninstall`, `pm hide`, `pm clear`, root or flashing. The only thing ever
  uninstalled is rehome's own two apps, and only on `restore --uninstall`.
- **Never disable what the TV or the household needs:** streaming apps, Assistant and voice search, DLNA, IP
  control, Chromecast, the firmware updaters, `org.droidtv.eum` (without it the sources drawer stops working),
  the Play Store and Play Services, the TV, tuner and HDMI services, Android's core packages, and
  `com.google.android.tv.remote.service` (the phone remote and keyboard in the Google Home app). The tool
  refuses a list that contains one; never edit that protection.
- **Only add packages to a list** with the person's agreement and a one-line reason, and never one of the above.
  A package whose role you cannot tell from its name: read `dumpsys package <name>`, then ask.
- **Never start a factory or test package**, not even to look; on some boards that flips a factory-mode flag.
- **Keep the Home guard:** the stock launcher is disabled only after rehome is installed, started, set as
  Home and still on screen a few seconds later. Never disable it by hand.
- **Back up first.** The tool does it; do not delete `~/.rehome/backups/`.
- **Keep the person's data private.** Never paste their IP address, Wi-Fi names, serial number, account names or
  backups into an issue, a commit or a public message.

## Changing this repo

Tests: `python3 -m unittest tools/test_rehome.py -v` (a fake TV, no device needed). Never run tests or
experiments against a real TV without its owner's go-ahead.
