# OLED care

Most of this family is OLED. An OLED panel ages where it shows the same bright image for long, and it looks after
itself with a care cycle that runs in standby. A few habits keep it healthy; none of them is specific to rehome.

## Switch off with the remote

- **Use the remote's power button, not the wall socket.** The panel's care cycle runs in standby after you switch off.
  Cutting the power stops it.
- **After a long viewing, wait 10–15 minutes before unplugging.** If you unplug the TV at night, leave it in standby
  for a while first.
- After the install, wait about 15 seconds before any unplugging: Android writes the disabled apps to disk with a
  delay.

## The Philips screensaver

After about **6 minutes without a key press on the remote**, the TV shows a Philips logo moving on black, after a
short notice. That is the panel's protection, and it starts even while something still moves on Home. Any key on the
remote closes it.

- Keys sent from a computer over ADB don't reset its 6-minute count, and usually don't close it. While you work on the
  TV from a computer, expect it every 6 minutes. A black screenshot with a Philips logo is this screensaver, not a
  fault.
- Android has its own screensaver too (Settings › Device Preferences › Screen saver), which can be rehome's
  **Clock**. On the tested set the firmware sets Android's timer to 7 days at every start, so in practice the Philips
  screensaver is the one that runs. That's fine: it does the job.
- To have rehome's clock come up on an idle Home instead, set `home_idle_clock_minutes` (in a theme's `config.json`
  or your own build). With `clock_wallpaper` and `clock_glow` set to `false`, the clock moves on plain black.

## How Home is built for OLED

- After a minute without key presses the player's progress bar stops too; what changes after that is the clock and,
  every 10 minutes, the wallpaper.
- The player's buttons are glass, never solid white, and the wallpaper is darkened by 55 %.
- The wallpaper changes every 10 minutes, with a crossfade.

Home is still a mostly still picture with some bright banners (Netflix and YouTube are nearly white). Don't leave it
on screen for hours; the Philips screensaver steps in after 6 minutes anyway.

## Picture settings

- **Energy saving** (Eco settings): Off or Minimum. Higher values cap the brightness, and the picture looks dim.
- **OLED contrast** is the panel's brightness. Leave it where the picture style puts it, and raise it only in a very
  bright room.
- More picture settings worth a look: [guide](guide.md#tv-settings-worth-a-look).
