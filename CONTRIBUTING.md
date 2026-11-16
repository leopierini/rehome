# Contributing to rehome

Thanks for helping! rehome is a small project maintained in spare time, so a short, focused change is the easiest to
review.

## Reports from your TV

rehome is tested on one 55OLED706. A report from any other model on the `PH1M_EA_9970A` board helps the most, even
when everything worked. [Open an issue](https://github.com/leopierini/rehome/issues/new) with:

- the model, board and firmware:

  ```sh
  adb shell getprop ro.boot.device_name
  adb shell getprop ro.product.board
  adb shell getprop ro.product.device
  adb shell getprop ro.build.fingerprint
  ```

  and the firmware version from Settings › Update software › Current software info;
- the rehome version (Settings › Apps › rehome);
- what you did, what you expected and what happened. For the installer, paste its output.

Leave out your IP address, Wi-Fi names, serial number and account names; trim them from pasted output.

## Pull requests

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Read [docs/ux.md](docs/ux.md) before changing how Home behaves: most choices there were tried both ways.
3. Add a test that fails without your change.
4. Before you push, run the tests (JDK 17 and the Android SDK 34, see the
   [README](README.md#build-it-yourself)):

   ```sh
   ./gradlew assembleRelease testReleaseUnitTest lintRelease
   python3 -m unittest tools/test_rehome.py
   ```

   The installer's tests use a fake TV; never point experiments at someone else's TV.
5. A new UI string needs its Italian translation in `values-it` too: lint fails without it.
6. A package added to a cleanup list needs a one-line reason, and must never be one the installer protects
   (streaming apps, Assistant, Chromecast, the Play Store, firmware updates, TV, tuner and HDMI services, the phone
   remote service).

On a TV with ADB on, the `rehome_test_*` global settings show states that are hard to reach by hand (offline, rain,
made-up player items); `app/src/main/java/io/github/leopierini/rehome/TestHooks.java` lists them. They do nothing
while ADB is off. For screenshots, `adb shell settings put global rehome_test_hide_debug 1` hides the red
**Debugging on** pill, so the header looks as it does with debugging off; `adb shell settings delete global
rehome_test_hide_debug` brings it back. Delete them all when you're done: `adb shell settings list global | grep
rehome_test` must print nothing.

## Releases

Releases are built with `tools/make-release.sh` and published by hand; the steps are in
[docs/releasing.md](docs/releasing.md).

By contributing, you agree that your contribution is licensed under the [MIT License](LICENSE).
