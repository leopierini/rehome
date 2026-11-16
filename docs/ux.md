# Why rehome works the way it does

Each choice below was made in front of the TV, most of them after trying the alternative. They are here so that a
change can start from the reason, not only from the result. How to use each part is in the [guide](guide.md).

## Home

- **Rows by category, not a grid.** A TV has a handful of apps used daily and a long tail used rarely. Titled rows
  (Media, Music, Games, Tools) put the daily ones in the first row and let the rest find their place by kind. New
  apps are placed automatically, so nothing needs sorting after an install.
- **Names only under the focused tile.** Almost every banner already carries the app's name; a second label under
  each one doubled the text on screen. The focused tile still shows it, and tiles without a banner always do.
- **No dimming of the tiles without focus.** The ring and glow already say where focus is. Grey has one meaning on
  Home: this app needs the internet and the TV is offline.
- **No "+N more" label.** The half-visible tile at the end of a row already says the row goes on.
- **A glow in the banner's colour, kept light.** A white ring alone gets lost on white banners (YouTube, Netflix).
  The glow takes its colour from the banner, so focus reads from across the room. The first version, modelled on the
  Play Store's, was too strong next to the player card and was toned down.
- **One focus language everywhere.** The pills, the weather, the dropdowns, the menus and the settings pages use the
  same ring, so focus looks the same wherever it is.
- **Home always lands on the first app in Media.** Pressing Home is a reset; the first app is the most used one.
- **Up from the first row is spatial, and the column is remembered.** Going down through rows of different lengths
  and back up returns to the column you started from.
- **Hold OK for a tile's menu, instead of an edit mode.** Move left or right, move to another row, hide, app info: all
  one long press away, and nothing to exit afterwards.
- **Digits and colour keys only on Home, with nothing open over it.** Inside apps the remote keeps its usual meaning,
  and an open dropdown never launches an app by accident. The colour keys start empty because their best use
  depends on the household.
- **Wallpapers darkened by 55 %.** White text has to read over any photo, bright skies included.

## The player

- **Above Media, not in the header and not as a row.** The header was already full. A row of "Continue watching"
  tiles under Media was tried first, but few apps publish to Android TV's Watch Next, so it mostly held one tile.
  A single wide card above the apps covers every app that plays something, and it's the first thing Up reaches.
- **No card when there's nothing to resume.** An empty card would be a permanent hole in the Home.
- **No title above the card, no "1 / 6".** Dots inside the card's top right corner show the position. Arrows at the
  edges appear only toward an item that exists.
- **Buttons aligned left, in the same place on every card.** ⏮ ⏯ ⏭ and Resume sit where the eye expects them,
  whatever the card. An "optical" alignment of Resume was tried and rejected.
- **The state ("Paused", "Playing") as dim text after the app's name, not as a pill.** The pill looked like a button.
- **Without a picture, the app's banner fills the space**, as on its tile. A small logo on a dark field looked broken.
- **Series on three lines:** title, episode name, then "Season 4, episode 10". Titles are cleaned of channel prefixes
  and tags like "(Official Video)".
- **The facts column doesn't repeat the centre.** Album and track, year, genre, length and when it was played, only
  where the centre doesn't already say it.
- **No " · " separators anywhere on Home.** Spacing and a thin bar separate items instead.
- **Without focus, the card shows the most recent item.** Browsing is temporary; coming back up starts from the top.
- **Only the most recently started session counts as playing.** Spotify and YouTube keep reporting "playing" after
  another app takes the audio, so the newest one wins. Ads are skipped, and anything shorter than 10 seconds isn't
  remembered.
- **⏯ resumes Spotify even when it's closed.** Opening an app only to find it paused is the thing the card is there to
  avoid.
- **Remove with a long press.** "Remove from player" sits where every other long-press menu is.
- **Glass buttons, never solid white, and a progress bar that stops after a minute without keys.** Large white areas
  and constant motion on an idle screen are what an OLED panel likes least.

## Header and weather

- **The city beside the temperature**, so the reading says where it is, with the sky on the line below and high, low,
  rain and wind on the third.
- **The Open-Meteo credit in Launcher settings, not on Home.** The licence asks for attribution; Home has no room for
  a line of small print, and settings is where people look for it.
- **The data counter inside the Wi-Fi dropdown, not in the header.** The header was too full. Data use belongs with
  the network anyway.
- **Weather starts with "Set your city".** Nothing is fetched until you pick one, so a new install sends no location
  anywhere.
- **A red "Debugging on" pill while ADB is on.** Network debugging left on is an open door on a shared hotspot. The
  pill is a reminder, and OK on it opens Developer options to turn it off.

## Wi-Fi

- **A dropdown, not the settings app.** Switching hotspots happens often on a TV that lives on phones. Android TV's
  settings take several screens for it; the dropdown takes one press.
- **Up on the first row closes a dropdown.** It's where focus came from, so Up going back there is what the hand
  expects. Back still works.
- **No lock on the connected network.** You're already on it; a lock there only says something you know.
- **"Saved" instead of a lock on saved networks.** A lock means "this will ask for a password". A saved network
  won't, so it says Saved.
- **Saved networks right after the connected one, in priority order.** They are the ones you'd switch to; the
  neighbours' networks come after, by signal.
- **Turn on keeps the dropdown open.** You turn Wi-Fi on to pick a network, so the list appears in place, with focus
  on the first one.
- **Picking a saved network closes the dropdown** and the pill shows a spinner and the network's name until the TV is
  on it. The result shows where you're looking, and a failure after 30 seconds says so.
- **Android's password screen only when a password is needed:** a network the TV has never joined, or a saved
  password that's rejected.
- **A priority order, applied by the launcher.** Android 11 has no priority between saved networks, so the launcher
  applies one after each scan. It only moves for a usable signal (−75 dBm or better), at most once a minute; a
  hotspot with no internet is set aside, longer each time it fails, so the TV doesn't bounce between two hotspots.
- **A network picked by hand wins over the order** until the TV leaves it. Otherwise the order would undo your choice
  at the next scan.
- **The order starts empty.** With no order, the TV behaves like stock Android.
- **Offline after 10 seconds, not at once.** Phone hotspots drop for a moment often; the offline Home only shows when
  the network is really gone.
- **Grey apps still open.** Some work partly offline, and a tile that does nothing reads as broken. The Tools row is
  never greyed: a dimmed Play Store or settings tile looks like a fault, and that row is where you fix things.
- **A scan every 45 seconds while offline.** Android on its own backs off to a scan every few minutes; asking more
  often means the TV is back on a hotspot moments after it's turned on.
- **A green, orange or red Wi-Fi icon.** Green: the internet works. Orange: connected, but the internet isn't
  confirmed yet (a hotspot with no data, or a sign-in page). Red: no network.

## Why there is a Wi-Fi helper app

Android 10 and later refuse `WifiManager.setWifiEnabled` and `enableNetwork` to apps that target Android 10 (API 29)
or newer: such apps can only open the system's Wi-Fi panels. The launcher targets API 34, as it must to be a current
app, so it can't turn Wi-Fi on or join a saved network itself.

An app that targets API 28 still can. The **rehome Wi-Fi helper** (`io.github.leopierini.rehome.wifi`) is that app,
and nothing else: no screen, no icon, about 8 KB. It has three commands: turn Wi-Fi on, join a saved network, and list
the saved networks (for the priority page). Only apps signed with the same key can send them, through a signature
permission, so no other app on the TV can drive it.

It needs location permission (including in the background, since it answers while the launcher is in front): Android
ties reading saved networks and scan results to location. It reads no position.

Because it targets an old Android version, Play Protect warns about it on the first install; press **Install anyway**.
Without it, the Wi-Fi dropdown still works through Android's own Wi-Fi screens, only with more presses, and the
priority order is unavailable.

## Language

English is the default, Italian the one translation. The other languages the launcher's base carried were dropped:
none of them had the text of the features added since, so they would have shown a mix of two languages. The clock
can be 12- or 24-hour; times inside the player and the rain line are 24-hour.
