#!/usr/bin/env python3
"""rehome installer: find a Philips Android TV on the network, back it up, clean it up, install rehome.

Usage (from the repo root; ./install.sh is the same as `tools/rehome.py install`):

  python3 tools/rehome.py install [--host IP] [--local] [--any-board] [--with-optional] [--animations]
                                  [--dry-run] [--yes]
  python3 tools/rehome.py backup  [--host IP]
  python3 tools/rehome.py check   [--host IP] [--fix] [--with-optional] [--any-board] [--yes]
  python3 tools/rehome.py restore [--host IP] [--uninstall] [--yes]

What it changes on the TV, and how each change is undone:
  - packages from cleanup/*.txt are disabled with `pm disable-user --user 0` (never uninstalled);
    `restore` enables them again;
  - the rehome launcher and its Wi-Fi companion are installed and given the permissions their features
    need; rehome becomes Home; `restore` gives Home back to the stock launcher and can uninstall them;
  - optionally, the system animation scales go to 0.25; `restore` puts the old values back.

Before the first change it saves the TV's state to ~/.rehome/backups/<serial>-<YYYYmmdd-HHMMSS>/. A backup is
never overwritten, and `restore` always uses the earliest one for that TV.

It needs Python 3.9+ and `adb` from Android platform-tools, and nothing else.
"""
import sys

if sys.version_info < (3, 9):
    sys.exit("rehome needs Python 3.9 or newer (this is %d.%d)." % sys.version_info[:2])

import argparse  # noqa: E402
import concurrent.futures  # noqa: E402
import datetime  # noqa: E402
import ipaddress  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import shutil  # noqa: E402
import socket  # noqa: E402
import subprocess  # noqa: E402
import tempfile  # noqa: E402
import time  # noqa: E402
import urllib.request  # noqa: E402
from pathlib import Path  # noqa: E402
from typing import Callable, Dict, Iterable, List, NamedTuple, Optional, Sequence, Set, Tuple  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
CLEANUP_DIR = REPO_ROOT / "cleanup"
MAIN_LIST = "ph1m_ea_9970a.txt"
OPTIONAL_LIST = "optional-preloads.txt"

LAUNCHER = "io.github.leopierini.rehome"
COMPANION = "io.github.leopierini.rehome.wifi"
LAUNCHER_HOME = LAUNCHER + "/.MainActivity"
MEDIA_LISTENER = LAUNCHER + "/.MediaListener"
STOCK_LAUNCHER = "com.google.android.tvlauncher"
STOCK_HOME_FALLBACK = STOCK_LAUNCHER + "/.MainActivity"
MIN_SDK = 26

KNOWN_BOARDS = {"PH1M_EA_9970A"}
# On the sets themselves ro.product.board reads TP1M_EA_9970A and ro.product.device PH1M_EA_9970A.
BOARD_PROPS = ("ro.product.board", "ro.product.device")
BOARD_ALIASES = {"TP1M_EA_9970A": "PH1M_EA_9970A"}

RELEASE_URL = "https://github.com/leopierini/rehome/releases/latest/download/{}"
APK_NAMES = ("rehome.apk", "rehome-wifi.apk")
ANIMATION_KEYS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
FAST_ANIMATIONS = "0.25"
ADB_PORT = 5555

HOME_QUERY = ("cmd package resolve-activity --brief -a android.intent.action.MAIN "
              "-c android.intent.category.HOME")
RESUMED_QUERY = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
# How long rehome must stay on screen, in the same process, before the stock launcher is disabled.
HOME_SETTLE_SECONDS = 3
TV_INPUT_QUERY = "pm query-services --components -a android.media.tv.TvInputService"

TAGS = ("needs-tvinput", "after-home")
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
COMPONENT_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")
SETTING_VALUE_RE = re.compile(r"^(null|[0-9]+(\.[0-9]+)?)$")

# Packages a cleanup list may never contain. The installer refuses the whole list if one is there.
PROTECTED = {
    # Play Store, Play Services, keyboard, Bluetooth
    "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
    "com.google.android.inputmethod.latin", "com.android.inputmethod.latin", "com.android.bluetooth",
    # the phone remote and keyboard in the Google Home app; Assistant and voice search
    "com.google.android.tv.remote.service", "com.google.android.katniss",
    # Chromecast built-in
    "com.google.android.apps.mediashell",
    # settings and setup (setupwraith can own the Wi-Fi network saved at setup)
    "com.android.tv.settings", "com.google.android.tungsten.setupwraith", "org.droidtv.settings",
    # TV engine, tuner, channels, inputs and HDMI
    "org.droidtv.xtv", "org.droidtv.xtvsystem", "org.droidtv.playtv", "org.droidtv.channels",
    "org.droidtv.epgdata", "org.droidtv.dvbi", "org.droidtv.teletext", "org.droidtv.ci",
    "org.droidtv.hdmiservice", "org.droidtv.euinstallertc", "org.droidtv.euinstallersat",
    "org.droidtv.hbbtvopapp", "org.droidtv.tv.tptvservice", "org.droidtv.icp", "org.droidtv.sunrise",
    "com.mediatek.tvinput", "com.mediatek.tvinputservice.arbitratorservice", "com.mediatek.tv.agent",
    "com.android.providers.tv",
    # bound by the TV engine: contextual help (without it the sources drawer dies) and the ambient screen
    "org.droidtv.eum", "org.droidtv.aurora",
    # shell, power, keys and remote
    "org.droidtv.tvsystemui", "org.droidtv.tvpowermanagerservice", "org.droidtv.GlobalKey", "org.droidtv.lkb",
    "org.droidtv.menudataapp", "org.droidtv.homeintentresolver",
    # DLNA and IP control
    "org.droidtv.dlna", "org.droidtv.ipcontrol",
    # firmware updates (TV and remote) and network setup
    "org.droidtv.oad", "com.easy.oad", "org.droidtv.devicesetup", "org.droidtv.networkwizard",
    "org.droidtv.nettvregistration",
    # browser engine (HbbTV, app gallery) and USB media
    "com.vewd.core.browserui", "org.droidtv.nettvbrowser", "org.droidtv.contentexplorer",
    # Ambilight, picture calibration and the audio path
    "org.droidtv.ambiair", "org.droidtv.ambihuecommon", "org.droidtv.alhuewizard",
    "org.droidtv.audiocalibration", "org.droidtv.calmancalibrator", "org.droidtv.bluetoothaudiosink",
    "org.droidtv.tv.playFiAudioService", "io.mimi.sdk.service",
    # streaming apps
    "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic", "com.google.android.youtube.tvkids", "com.google.android.videos",
    "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.spotify.tv.android",
    "com.amazon.music.tv", "it.rainet.androidtv",
    # rehome itself
    LAUNCHER, COMPANION,
}
CORE_EXACT = {"android", "android.ext.services", "android.ext.shared", "com.android.systemui",
              "com.android.shell", "com.android.phone", "com.android.settings", "com.android.tv.settings"}
CORE_PARTS = ("permissioncontroller", "packageinstaller", "ext.services", "ext.shared", "modulemetadata")
PROVIDERS_ALLOWED = {"com.android.providers.calendar", "com.android.providers.contacts",
                     "com.android.providers.userdictionary", "com.android.providers.blockednumber"}


class RehomeError(Exception):
    """Something went wrong; the message says what and what was left as it was."""


class Refused(RehomeError):
    """rehome stopped on purpose, before changing anything it was not allowed to change."""


class ListError(RehomeError):
    pass


class ProtectedPackageError(RehomeError):
    pass


class ConnectionLost(RehomeError):
    pass


# --------------------------------------------------------------------------------------------- lists

class Entry(NamedTuple):
    package: str
    reason: str
    group: str
    tags: Tuple[str, ...]

    @property
    def needs_tvinput(self) -> bool:
        return "needs-tvinput" in self.tags

    @property
    def after_home(self) -> bool:
        return "after-home" in self.tags or self.package == STOCK_LAUNCHER


def parse_list(text: str, source: str = "list") -> List[Entry]:
    """Parse a cleanup list: `[group]` headers, `package  # reason` lines, `#` comments, blank lines."""
    entries: List[Entry] = []
    seen: Set[str] = set()
    group = ""
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        where = "%s line %d" % (source, n)
        if line.startswith("["):
            if not line.endswith("]") or not line[1:-1].strip():
                raise ListError("%s: a group header looks like [Group name]: %r" % (where, raw))
            group = line[1:-1].strip()
            continue
        pkg, _, reason = line.partition("#")
        pkg, reason = pkg.strip(), reason.strip()
        if not PACKAGE_RE.match(pkg):
            raise ListError("%s: not a package name: %r" % (where, pkg))
        if pkg in seen:
            raise ListError("%s: %s is listed twice" % (where, pkg))
        seen.add(pkg)
        tags: List[str] = []
        while True:
            m = re.match(r"(%s):\s*" % "|".join(map(re.escape, TAGS)), reason)
            if not m:
                break
            tags.append(m.group(1))
            reason = reason[m.end():]
        entries.append(Entry(pkg, reason, group, tuple(tags)))
    return entries


def load_list(path) -> List[Entry]:
    path = Path(path)
    return parse_list(path.read_text(encoding="utf-8"), source=path.name)


def is_core(pkg: str) -> bool:
    """Android's own packages: without them the TV may not boot."""
    if pkg in CORE_EXACT or pkg.startswith("com.google.android.overlay.modules"):
        return True
    if pkg.startswith(("com.android.", "com.google.android.")) and any(p in pkg for p in CORE_PARTS):
        return True
    return pkg.startswith("com.android.providers.") and pkg not in PROVIDERS_ALLOWED


def is_protected(pkg: str) -> bool:
    return pkg in PROTECTED or is_core(pkg)


def check_protected(entries: Iterable[Entry]) -> None:
    bad = sorted({e.package for e in entries if is_protected(e.package)})
    if bad:
        raise ProtectedPackageError(
            "the cleanup list contains packages the TV or the household needs, so nothing was changed: "
            + ", ".join(bad) + ". Remove them from the list.")


class Plan(NamedTuple):
    now: List[Entry]                     # disabled in the cleanup step
    after_home: List[Entry]              # disabled once rehome is Home; the stock launcher first
    skipped: List[Tuple[Entry, str]]     # left alone, with the reason


def plan_disable(entries: Sequence[Entry], installed: Set[str], disabled: Set[str],
                 tvinput_providers: Iterable[str] = ()) -> Plan:
    check_protected(entries)
    providers = set(tvinput_providers)
    now: List[Entry] = []
    later: List[Entry] = []
    skipped: List[Tuple[Entry, str]] = []
    for e in entries:
        if e.package not in installed:
            skipped.append((e, "not installed on this TV"))
        elif e.package in disabled:
            skipped.append((e, "already disabled"))
        elif e.package in providers:
            skipped.append((e, "provides a TV input on this TV, so it is left alone"))
        elif e.after_home:
            later.append(e)
        else:
            now.append(e)
    later.sort(key=lambda e: e.package != STOCK_LAUNCHER)
    return Plan(now, later, skipped)


# --------------------------------------------------------------------------------------------- adb

Runner = Callable[..., Tuple[int, str]]


def run_adb(argv: Sequence[str], timeout: Optional[float] = 120) -> Tuple[int, str]:
    try:
        p = subprocess.run(list(argv), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                           timeout=timeout)
    except FileNotFoundError:
        raise RehomeError("adb was not found. " + ADB_HELP)
    except subprocess.TimeoutExpired:
        raise ConnectionLost("adb did not answer within %d s (%s)" % (timeout, " ".join(argv[1:3])))
    return p.returncode, p.stdout or ""


class Device:
    def __init__(self, serial: str, runner: Runner = run_adb):
        self.serial = serial
        self.runner = runner

    def adb(self, *args: str, timeout: Optional[float] = 120) -> Tuple[int, str]:
        return self.runner(["adb", "-s", self.serial] + list(args), timeout=timeout)

    def shell(self, cmd: str) -> str:
        rc, out = self.adb("shell", cmd)
        if rc != 0 and out.lstrip().lower().startswith("error:") and "exception" not in out.lower():
            raise ConnectionLost("lost the TV (%s): %s" % (self.serial, out.strip()))
        return out.replace("\r", "")

    def getprop(self, name: str) -> str:
        return self.shell("getprop " + name).strip()

    def packages(self, flag: str = "") -> Set[str]:
        out = self.shell(("pm list packages " + flag).strip())
        return {line[len("package:"):].strip() for line in out.splitlines() if line.startswith("package:")}

    def installed(self) -> Set[str]:
        return self.packages()

    def disabled(self) -> Set[str]:
        return self.packages("-d")

    def tvinput_providers(self) -> Set[str]:
        out = self.shell(TV_INPUT_QUERY)
        return {line.split("/", 1)[0].strip() for line in out.splitlines() if "/" in line}

    def home(self) -> str:
        lines = [x.strip() for x in self.shell(HOME_QUERY).splitlines() if x.strip()]
        return lines[-1] if lines else ""

    def home_of(self, pkg: str) -> str:
        lines = [x.strip() for x in self.shell(HOME_QUERY + " " + pkg).splitlines() if x.strip()]
        last = lines[-1] if lines else ""
        return last if COMPONENT_RE.match(last) and last.startswith(pkg + "/") else ""

    def resumed(self) -> str:
        """The activity on screen ("pkg/.Class"), or "" when the TV does not say."""
        lines = self.shell(RESUMED_QUERY).splitlines()
        top = [x for x in lines if "topResumedActivity" in x] or [x for x in lines if "mResumedActivity" in x]
        m = re.search(r"ActivityRecord\{\S+ u\d+ (\S+)", top[0]) if top else None
        return m.group(1) if m else ""

    def pid(self, pkg: str) -> str:
        return self.shell("pidof " + _pkg(pkg)).strip()

    def set_home(self, component: str) -> str:
        if not COMPONENT_RE.match(component):
            raise RehomeError("not a component name: %r" % component)
        return self.shell("cmd package set-home-activity --user 0 " + component)

    def disable(self, pkg: str) -> bool:
        return "new state: disabled" in self.shell("pm disable-user --user 0 " + _pkg(pkg))

    def enable(self, pkg: str) -> bool:
        return "new state: enabled" in self.shell("pm enable --user 0 " + _pkg(pkg))

    def setting(self, namespace: str, key: str) -> str:
        return self.shell("settings get %s %s" % (namespace, key)).strip()


def _pkg(pkg: str) -> str:
    if not PACKAGE_RE.match(pkg):
        raise RehomeError("not a package name: %r" % pkg)
    return pkg


# --------------------------------------------------------------------------------------------- talking to the user

class Ui:
    def __init__(self, yes: bool = False, interactive: Optional[bool] = None,
                 out: Callable[[str], None] = print, ask: Callable[[str], str] = input):
        self.yes = yes
        self.interactive = sys.stdin.isatty() if interactive is None else interactive
        self.out = out
        self.ask = ask

    def say(self, msg: str = "") -> None:
        self.out(msg)

    def step(self, n: int, title: str) -> None:
        self.out("")
        self.out("== Step %d: %s" % (n, title))

    def require_consent(self) -> None:
        if not self.yes and not self.interactive:
            raise Refused("there is no terminal to ask for confirmation, so nothing was changed. Read the plan "
                          "(install --dry-run, or check) and run again with --yes.")

    def confirm(self, question: str) -> bool:
        if self.yes:
            self.out(question + " yes (--yes)")
            return True
        self.require_consent()
        return self.ask(question + " [y/N] ").strip().lower() in ("y", "yes")

    def offer(self, question: str, flag: bool) -> bool:
        """An optional extra: on with its flag; asked only when there is someone to ask and no --yes."""
        if flag:
            return True
        if self.yes or not self.interactive:
            return False
        return self.ask(question + " [y/N] ").strip().lower() in ("y", "yes")


# --------------------------------------------------------------------------------------------- the TV

def read_props(dev: Device) -> Dict[str, str]:
    keys = ("ro.product.board", "ro.product.device", "ro.boot.device_name", "ro.product.model",
            "ro.build.version.sdk", "ro.build.fingerprint")
    return {k: dev.getprop(k) for k in keys}


def board_known(props: Dict[str, str]) -> bool:
    for key in BOARD_PROPS:
        value = props.get(key, "").strip()
        if BOARD_ALIASES.get(value, value) in KNOWN_BOARDS:
            return True
    return False


def board_verdict(props: Dict[str, str], any_board: bool) -> str:
    """'known' (clean up and install), 'unknown' (--any-board: install only), or Refused."""
    if board_known(props):
        return "known"
    seen = " / ".join(props.get(k) or "?" for k in BOARD_PROPS)
    if any_board:
        return "unknown"
    raise Refused("this TV's board is %s, not one rehome knows (%s). Nothing was changed. The cleanup list is "
                  "only for that board; --any-board installs the launcher without any cleanup."
                  % (seen, ", ".join(sorted(KNOWN_BOARDS))))


def describe(props: Dict[str, str]) -> str:
    return "%s (%s), board %s, device %s, Android SDK %s" % (
        props.get("ro.boot.device_name") or "unknown model", props.get("ro.product.model") or "?",
        props.get("ro.product.board") or "?", props.get("ro.product.device") or "?",
        props.get("ro.build.version.sdk") or "?")


def tv_id(dev: Device) -> str:
    serial = dev.getprop("ro.serialno") or dev.serial
    return re.sub(r"[^A-Za-z0-9._]", "_", serial) or "unknown"


# --------------------------------------------------------------------------------------------- backups

def backups_root() -> Path:
    return Path(os.environ.get("REHOME_HOME") or "~/.rehome").expanduser() / "backups"


def _backup_key(path: Path, serial: str):
    m = re.fullmatch(re.escape(serial) + r"-(\d{8}-\d{6})(?:-(\d+))?", path.name)
    return (m.group(1), int(m.group(2) or 1)) if m else None


def list_backups(root: Path, serial: str) -> List[Path]:
    """This TV's backups, earliest first."""
    root = Path(root)
    if not root.is_dir():
        return []
    found = [(k, p) for p in root.iterdir() if p.is_dir() for k in [_backup_key(p, serial)] if k]
    return [p for _, p in sorted(found)]


def earliest_backup(root: Path, serial: str) -> Optional[Path]:
    found = list_backups(root, serial)
    return found[0] if found else None


def make_backup(dev: Device, root: Path, now: Optional[datetime.datetime] = None) -> Path:
    """Save the TV's state into a new folder; an existing backup is never touched."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    serial = tv_id(dev)
    files = {
        "packages.txt": dev.shell("pm list packages -u"),
        "disabled.txt": dev.shell("pm list packages -d"),
        "home.txt": dev.shell(HOME_QUERY),
        "animations.txt": "".join("%s=%s\n" % (k, dev.setting("global", k)) for k in ANIMATION_KEYS),
        "tv.txt": "".join("%s=%s\n" % kv for kv in read_props(dev).items()),
    }
    tmp = Path(tempfile.mkdtemp(prefix=".partial-", dir=str(root)))
    for name, text in files.items():
        (tmp / name).write_text(text, encoding="utf-8")
    stamp = (now or datetime.datetime.now()).strftime("%Y%m%d-%H%M%S")
    n = 1
    while True:
        target = root / ("%s-%s%s" % (serial, stamp, "" if n == 1 else "-%d" % n))
        if not target.exists():
            try:
                os.rename(str(tmp), str(target))
                return target
            except OSError:
                if not target.exists():
                    raise
        n += 1


class Backup(NamedTuple):
    path: Path
    disabled: Set[str]
    home: str
    animations: Dict[str, str]
    packages: Set[str]                   # every package on the TV at backup time (pm list packages -u)


def _package_lines(path: Path) -> Set[str]:
    if not path.exists():
        return set()
    return {x[len("package:"):].strip() for x in path.read_text().splitlines() if x.startswith("package:")}


def read_backup(path: Path) -> Backup:
    path = Path(path)
    disabled = _package_lines(path / "disabled.txt")
    home_lines = [x.strip() for x in (path / "home.txt").read_text().splitlines() if x.strip()]
    anim: Dict[str, str] = {}
    for line in (path / "animations.txt").read_text().splitlines():
        key, _, value = line.partition("=")
        if key in ANIMATION_KEYS and SETTING_VALUE_RE.match(value.strip()):
            anim[key] = value.strip()
    return Backup(path, disabled, home_lines[-1] if home_lines else "", anim, _package_lines(path / "packages.txt"))


def applied_packages(root: Path, serial: str) -> Set[str]:
    """Everything an earlier install disabled on this TV (from applied.txt in its backup folders)."""
    out: Set[str] = set()
    for b in list_backups(root, serial):
        f = b / "applied.txt"
        if f.exists():
            out |= {x.strip() for x in f.read_text().splitlines() if PACKAGE_RE.match(x.strip())}
    return out


# --------------------------------------------------------------------------------------------- changes

def apply_disable(dev: Device, ui: Ui, entries: Sequence[Entry]) -> List[str]:
    done: List[str] = []
    failed: List[Entry] = []
    for e in entries:
        if dev.disable(e.package):
            ui.say("  disabled  %-42s %s" % (e.package, e.reason))
            done.append(e.package)
        else:
            failed.append(e)
    if done:
        # This firmware sometimes leaves one package enabled after a batch: read back and retry once.
        still = [p for p in done if p not in dev.disabled()]
        for p in still:
            if not dev.disable(p):
                ui.say("  WARNING: %s did not stay disabled" % p)
    for e in failed:
        ui.say("  WARNING: could not disable %s; left as it was" % e.package)
    return done


def hand_over_home(dev: Device, ui: Ui, after_home: Sequence[Entry]) -> List[str]:
    """Make rehome Home, and only then disable the stock launcher (and what depends on it).

    If Home does not end up on rehome, the stock launcher is put back, so the TV always has a Home."""
    out = dev.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                    + LAUNCHER_HOME)
    if "Status: ok" not in out or re.search(r"^Error", out, re.M):
        last = out.strip().splitlines()[-1] if out.strip() else "no answer"
        raise RehomeError("rehome did not start (%s); the stock launcher was left enabled and Home did not move."
                          % last)
    ui.say("  rehome started")
    pid = dev.pid(LAUNCHER)
    stock_home = dev.home_of(STOCK_LAUNCHER) or STOCK_HOME_FALLBACK
    dev.set_home(LAUNCHER_HOME)
    ui.say("  Home set to rehome")
    # "Status: ok" only says the activity started. A crash a moment later would leave a TV whose only Home
    # crash-loops once the stock launcher is gone: wait, and require rehome still on screen in the same process.
    time.sleep(HOME_SETTLE_SECONDS)
    on_screen = dev.resumed()
    pid_now = dev.pid(LAUNCHER)
    shown = on_screen in (LAUNCHER_HOME, LAUNCHER + "/" + LAUNCHER + ".MainActivity")
    if not shown or (pid and pid_now != pid):
        dev.set_home(stock_home)
        why = "its process restarted, so it crashed" if shown else "on screen now: %s" % (on_screen or "unknown")
        raise RehomeError("rehome started but did not stay on screen (%s). Nothing else was disabled: the stock "
                          "launcher was left enabled and is Home again. Please report this (see CONTRIBUTING.md); "
                          "`python3 tools/rehome.py restore` undoes the cleanup." % why)
    ui.say("  rehome is on screen")
    done: List[str] = []
    try:
        for e in after_home:
            if dev.disable(e.package):
                ui.say("  disabled  %-42s %s" % (e.package, e.reason))
                done.append(e.package)
            else:
                ui.say("  WARNING: could not disable %s" % e.package)
        if done:
            dev.set_home(LAUNCHER_HOME)  # Android drops the Home preference when the set of Home apps changes
        now = dev.home()
    except ConnectionLost as e:
        if not done:
            raise
        fix = "; ".join("adb -s %s shell pm enable --user 0 %s" % (dev.serial, p) for p in reversed(done))
        raise ConnectionLost("%s. The TV was cut off during the Home handover. If it has no working Home, "
                             "reconnect and run: %s; adb -s %s shell cmd package set-home-activity --user 0 %s"
                             % (e, fix, dev.serial, stock_home))
    if now.startswith(LAUNCHER + "/"):
        ui.say("  Home is rehome: " + now)
        return done
    if done:
        for p in reversed(done):
            dev.enable(p)
        dev.set_home(stock_home)
        raise RehomeError("Home went to %r instead of rehome, so %s was enabled again and is Home." % (
            now or "nothing", ", ".join(done)))
    ui.say("  NOTE: Home still resolves to %r. Press Home on the remote and pick rehome (Always)." % now)
    return done


def grant_permissions(dev: Device, ui: Ui, companion_installed: bool) -> None:
    if companion_installed:
        for perm in ("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"):
            dev.shell("pm grant %s android.permission.%s" % (COMPANION, perm))
        if "android.permission.ACCESS_BACKGROUND_LOCATION: granted=true" in dev.shell("dumpsys package " + COMPANION):
            ui.say("  Wi-Fi companion: location granted")
        else:
            ui.say("  WARNING: the Wi-Fi companion has no location grant: picking a saved network opens the "
                   "TV's Wi-Fi settings instead")
    dev.shell("pm grant %s android.permission.ACCESS_FINE_LOCATION" % LAUNCHER)
    dev.shell("pm grant %s android.permission.READ_TV_LISTINGS" % LAUNCHER)
    dev.shell("cmd appops set %s GET_USAGE_STATS allow" % LAUNCHER)
    dev.shell("appops write-settings")  # app-ops reach the disk lazily; save it now
    dev.shell("cmd notification allow_listener " + MEDIA_LISTENER)
    dev.shell("cmd package compile -m speed -f " + LAUNCHER)
    perms = dev.shell("dumpsys package " + LAUNCHER)
    checks = [
        ("android.permission.ACCESS_FINE_LOCATION: granted=true" in perms,
         "location (Wi-Fi name and network list)"),
        ("android.permission.READ_TV_LISTINGS: granted=true" in perms, "TV listings (series in the player)"),
        ("allow" in dev.shell("cmd appops get %s GET_USAGE_STATS" % LAUNCHER), "usage stats (data counter)"),
        (MEDIA_LISTENER in dev.setting("secure", "enabled_notification_listeners")
         or (LAUNCHER + "/" in dev.setting("secure", "enabled_notification_listeners")),
         "media listener (the player card)"),
    ]
    for ok, what in checks:
        ui.say(("  granted   " if ok else "  WARNING: not granted: ") + what)


def install_apk(dev: Device, path: str) -> Tuple[bool, str]:
    rc, out = dev.adb("install", "-r", str(path), timeout=600)
    return ("Success" in out), out.strip()


# --------------------------------------------------------------------------------------------- APKs

class Apks(NamedTuple):
    launcher: str
    companion: str


def local_apks(root: Path = REPO_ROOT) -> Apks:
    def pick(module: str) -> str:
        found = sorted((root / module / "build" / "outputs" / "apk" / "release").glob("*.apk"),
                       key=lambda p: ("unsigned" in p.name, p.name))
        if not found:
            raise RehomeError("no APK in %s/build/outputs/apk/release: build it first with "
                              "./gradlew assembleRelease" % module)
        return str(found[0])
    return Apks(pick("app"), pick("wifi"))


def download_apks(dest: Path, opener=urllib.request.urlopen) -> Apks:
    paths = []
    for name in APK_NAMES:
        url = RELEASE_URL.format(name)
        try:
            with opener(url, timeout=120) as r:
                data = r.read()
        except OSError as e:
            raise RehomeError("could not download %s (%s); check the internet connection, or build the "
                              "APKs and use --local" % (url, e))
        if not data.startswith(b"PK"):
            raise RehomeError("%s is not an APK" % url)
        p = Path(dest) / name
        p.write_bytes(data)
        paths.append(str(p))
    return Apks(*paths)


# --------------------------------------------------------------------------------------------- commands

MANUAL_STEPS = """\
What is left to do by hand:
  1. Play Protect: if the TV showed "App blocked" for rehome's Wi-Fi companion, press "Install anyway" with the
     remote, then run `./install.sh` again with the same options. That is safe: it skips the packages already
     disabled, installs both apps again over themselves, grants the permissions and confirms rehome as Home.
     The companion targets an old Android version on purpose: it is the only way Android 11 lets an app switch
     Wi-Fi on and join a saved network.
  2. OLED sets: switch the TV off with the remote, not at the wall; the panel's care cycle runs in standby.
     More tips: https://github.com/leopierini/rehome/blob/main/docs/oled.md
  3. Wait about 15 seconds before unplugging the TV: Android writes the disabled state to disk with a delay.
  4. Close ADB when you are done (until then port 5555 is open to anyone on your network): Settings >
     Device Preferences > Developer options > USB debugging: Off, then "Revoke USB debugging authorizations",
     then restart the TV from its menu (Settings > Device Preferences > About > Restart).
After a firmware update: python3 tools/rehome.py check --fix
To undo everything:      python3 tools/rehome.py restore"""


def _cleanup_entries(opts, ui: Ui, ask: bool = True) -> List[Entry]:
    entries = load_list(CLEANUP_DIR / MAIN_LIST)
    optional = load_list(CLEANUP_DIR / OPTIONAL_LIST)
    check_protected(entries + optional)
    if not ask:
        return entries
    ui.say("Optional group (%s): %s" % (OPTIONAL_LIST, ", ".join("%s (%s)" % (e.package, e.reason)
                                                                 for e in optional)))
    if ui.offer("Disable the optional preloads too?", getattr(opts, "with_optional", False)):
        return entries + optional
    ui.say("  optional group left alone")
    return entries


def _show_plan(ui: Ui, plan: Plan) -> None:
    group = None
    for e in plan.now + plan.after_home:
        if e.group != group:
            group = e.group
            ui.say("  [%s]" % group)
        note = " (after rehome is Home)" if e.after_home else ""
        ui.say("    %-42s %s%s" % (e.package, e.reason, note))
    for e, why in plan.skipped:
        ui.say("  skip  %-42s %s" % (e.package, why))


def install(dev: Device, ui: Ui, opts, apks, root: Path) -> None:
    """Steps 3-9 of the install, on a connected TV. `apks` is an Apks or a function that returns one."""
    dry = getattr(opts, "dry_run", False)
    if not dry:
        ui.require_consent()

    ui.step(3, "Check the TV")
    props = read_props(dev)
    ui.say("  " + describe(props))
    verdict = board_verdict(props, getattr(opts, "any_board", False))
    sdk = int(re.sub(r"\D", "", props.get("ro.build.version.sdk") or "0") or 0)
    if sdk < MIN_SDK:
        raise Refused("rehome needs Android 8 (SDK %d) or newer; this TV reports SDK %s. Nothing was changed."
                      % (MIN_SDK, sdk or "?"))
    if verdict == "unknown":
        ui.say("  Unknown board: --any-board installs the launcher only, without any cleanup.")

    ui.step(4, "Back up the TV's state")
    backup = make_backup(dev, root)
    ui.say("  saved to " + str(backup))

    plan = Plan([], [], [])
    if verdict == "known":
        ui.step(5, "Cleanup plan (disable, never uninstall)")
        entries = _cleanup_entries(opts, ui)
        plan = plan_disable(entries, dev.installed(), dev.disabled(), dev.tvinput_providers())
        _show_plan(ui, plan)
        ui.say("  %d to disable now, %d after rehome is Home, %d skipped"
               % (len(plan.now), len(plan.after_home), len(plan.skipped)))
    ui.say("")
    ui.say("Then: install rehome and its Wi-Fi companion (adb install -r), grant location, TV listings, usage "
           "stats and the media listener, make rehome Home" + (" and disable the stock launcher last."
                                                                  if plan.after_home else "."))
    if dry:
        ui.say("Dry run: nothing was changed on the TV.")
        return
    # Get both APKs before anything changes: a missing release must not leave a cleaned TV without rehome.
    ui.say("Getting the rehome APKs" + (" from the local build" if getattr(opts, "local", False)
                                        else " from the latest release") + " ...")
    try:
        apks = apks() if callable(apks) else apks
    except RehomeError as e:
        raise RehomeError("%s. Nothing was changed on the TV." % e)
    ui.say("  launcher: %s\n  Wi-Fi companion: %s" % (apks.launcher, apks.companion))
    if not ui.confirm("Apply these changes to the TV?"):
        raise Refused("nothing was changed.")

    applied: List[str] = []
    if plan.now:
        ui.step(6, "Disable the cleanup list")
        applied += apply_disable(dev, ui, plan.now)
        _write_applied(backup, applied)

    ui.step(7, "Install rehome")
    ok, out = install_apk(dev, apks.launcher)
    if not ok:
        hint = (" An older rehome signed with another key is installed: uninstall it first."
                if "UPDATE_INCOMPATIBLE" in out else "")
        raise RehomeError("the launcher did not install (%s); Home and the stock launcher were left alone.%s"
                          % (out.splitlines()[-1] if out else "no answer", hint))
    ui.say("  launcher installed")
    ui.say("  Installing the Wi-Fi companion. Watch the TV: if Play Protect says \"App blocked\", press "
           "\"Install anyway\" with the remote.")
    companion_ok, out = install_apk(dev, apks.companion)
    ui.say("  Wi-Fi companion installed" if companion_ok else
           "  WARNING: the Wi-Fi companion did not install (%s); the Wi-Fi menu falls back to the TV's own "
           "screens. If Play Protect blocked it, press \"Install anyway\" on the TV, then run ./install.sh again "
           "(it is safe to repeat)." % (out.splitlines()[-1] if out else "no answer"))
    grant_permissions(dev, ui, companion_ok)
    try:
        applied += hand_over_home(dev, ui, plan.after_home)
    finally:
        _write_applied(backup, applied)

    ui.step(8, "Animations (optional)")
    if ui.offer("Set the system animation scale to %s (snappier menus; restore undoes it)?" % FAST_ANIMATIONS,
                getattr(opts, "animations", False)):
        for key in ANIMATION_KEYS:
            dev.shell("settings put global %s %s" % (key, FAST_ANIMATIONS))
        ui.say("  animation scales set to " + FAST_ANIMATIONS)
    else:
        ui.say("  left as they were (use --animations to set them)")

    ui.step(9, "Done")
    ui.say(MANUAL_STEPS)


def _write_applied(backup: Path, applied: Sequence[str]) -> None:
    (Path(backup) / "applied.txt").write_text("".join(p + "\n" for p in applied), encoding="utf-8")


def backup_command(dev: Device, ui: Ui, opts, root: Path) -> None:
    path = make_backup(dev, root)
    ui.say("Backup saved to " + str(path))
    first = earliest_backup(root, tv_id(dev))
    if first != path:
        ui.say("restore will still use the earliest backup of this TV: " + str(first))


def restore(dev: Device, ui: Ui, opts, root: Path) -> None:
    ui.require_consent()
    serial = tv_id(dev)
    first = earliest_backup(root, serial)
    if first is None:
        raise Refused("there is no backup of this TV in %s, so restore does not know what was enabled before. "
                      "Nothing was changed." % root)
    b = read_backup(first)
    ui.say("Restoring from the earliest backup of this TV: " + str(first))
    # Only packages that were there at backup time (or that rehome disabled): one installed and disabled since is
    # not rehome's to enable.
    known = b.packages | applied_packages(root, serial)
    to_enable = sorted((dev.disabled() - b.disabled) & known, key=lambda p: (p == STOCK_LAUNCHER, p))
    home = b.home if COMPONENT_RE.match(b.home) and b.home.split("/")[0] not in ("android", LAUNCHER) else ""
    ui.say("  enable again: " + (", ".join(to_enable) if to_enable else "nothing"))
    ui.say("  Home back to: " + (home or "the stock launcher"))
    ui.say("  animation scales: " + (", ".join("%s=%s" % kv for kv in b.animations.items()) or "as they are"))
    if not ui.confirm("Restore the TV?"):
        raise Refused("nothing was changed.")
    for p in to_enable:
        ui.say(("  enabled   " if dev.enable(p) else "  WARNING: could not enable ") + p)
    target = home or dev.home_of(STOCK_LAUNCHER)
    if target:
        dev.set_home(target)
    now = dev.home()
    ui.say("  Home is now " + (now or "?"))
    for key, value in b.animations.items():
        if value == "null":
            dev.shell("settings delete global " + key)
        else:
            dev.shell("settings put global %s %s" % (key, value))
    if not ui.offer("Uninstall the rehome launcher and its Wi-Fi companion as well?",
                    getattr(opts, "uninstall", False)):
        ui.say("rehome stays installed (uninstall it later with: restore --uninstall).")
        return
    if now.startswith(LAUNCHER + "/"):
        raise RehomeError("Home is still rehome, so it was not uninstalled. Press Home, pick the stock launcher, "
                          "and run restore --uninstall again.")
    installed = dev.installed()
    for pkg in (COMPANION, LAUNCHER):  # rehome's own apps, never a system package
        if pkg in installed:
            rc, out = dev.adb("uninstall", pkg)
            ui.say(("  uninstalled " if "Success" in out else "  WARNING: could not uninstall ") + pkg)


def check(dev: Device, ui: Ui, opts, root: Path) -> int:
    """Report whether the cleanup and Home are still in place; with --fix, put them back. 0 = all in place."""
    fix = getattr(opts, "fix", False)
    if fix:
        ui.require_consent()
    props = read_props(dev)
    ui.say(describe(props))
    verdict = board_verdict(props, getattr(opts, "any_board", False))
    entries: List[Entry] = []
    if verdict == "known":
        entries = load_list(CLEANUP_DIR / MAIN_LIST)
        optional = load_list(CLEANUP_DIR / OPTIONAL_LIST)
        check_protected(entries + optional)
        applied = applied_packages(root, tv_id(dev))
        entries += [e for e in optional if getattr(opts, "with_optional", False) or e.package in applied]
    installed, disabled = dev.installed(), dev.disabled()
    # "Enabled again" is what --fix would disable: not an entry install left alone because it provides a TV input.
    plan = plan_disable(entries, installed, disabled, dev.tvinput_providers())
    back = plan.now + plan.after_home
    has_launcher = LAUNCHER in installed and LAUNCHER not in disabled
    home = dev.home()
    home_ok = home.startswith(LAUNCHER + "/")
    for e in back:
        ui.say("  enabled again: %-38s %s" % (e.package, e.reason))
    ui.say("  rehome installed: " + ("yes" if has_launcher else "NO"))
    ui.say("  Home: %s%s" % (home or "?", "" if home_ok else "  (not rehome)"))
    if not back and has_launcher and home_ok:
        ui.say("Everything is in place.")
        return 0
    if not fix:
        ui.say("Run `python3 tools/rehome.py check --fix` to put it back.")
        return 1
    if not ui.confirm("Disable those again and make rehome Home?"):
        raise Refused("nothing was changed.")
    # Like install: a backup first, and what this run disables in its applied.txt, so restore (which enables only
    # what the earliest backup knew or rehome disabled) also undoes a package a firmware update added since.
    backup = None
    if plan.now or (has_launcher and plan.after_home):
        backup = make_backup(dev, root)
        ui.say("  backup saved to " + str(backup))
    applied: List[str] = []
    try:
        applied += apply_disable(dev, ui, plan.now)
        if has_launcher:
            applied += hand_over_home(dev, ui, plan.after_home)
    finally:
        if backup is not None:
            _write_applied(backup, applied)
    if not has_launcher:
        ui.say("rehome is not installed: run install to put it back.")
        return 1
    still = [e.package for e in plan.now + plan.after_home if e.package not in dev.disabled()]
    if still or not dev.home().startswith(LAUNCHER + "/"):
        ui.say("Not everything could be fixed: " + ", ".join(still or ["Home"]))
        return 1
    ui.say("Fixed.")
    return 0


# --------------------------------------------------------------------------------------------- finding the TV

ADB_HELP = ("Install Android platform-tools: macOS `brew install android-platform-tools`, Debian/Ubuntu "
            "`sudo apt install adb`, or download them from https://developer.android.com/tools/releases/"
            "platform-tools and put adb on your PATH.")


def check_prereqs(ui: Ui) -> None:
    ui.step(1, "Check the tools on this computer")
    ui.say("  Python %d.%d" % sys.version_info[:2])
    if not shutil.which("adb"):
        raise RehomeError("adb was not found. " + ADB_HELP)
    ui.say("  adb: " + shutil.which("adb"))


def local_ip() -> Optional[str]:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # UDP: picks the outgoing interface, sends nothing
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def subnet_hosts(ip: str) -> List[str]:
    net = ipaddress.ip_network(ip + "/24", strict=False)
    return [str(h) for h in net.hosts() if str(h) != ip]


def scan(hosts: Iterable[str], port: int = ADB_PORT, timeout: float = 0.3,
         connect=socket.create_connection) -> List[str]:
    def probe(host: str) -> Optional[str]:
        try:
            connect((host, port), timeout).close()
            return host
        except OSError:
            return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        found = [h for h in pool.map(probe, list(hosts)) if h]
    return sorted(found, key=ipaddress.ip_address)


def find_host(opts, ui: Ui) -> str:
    ui.step(2, "Find the TV")
    if opts.host:
        return opts.host
    ip = local_ip()
    if not ip:
        raise RehomeError("could not tell which network this computer is on; pass the TV's address with --host")
    ui.say("  Looking for TVs with ADB open on port %d in %s/24 ..." % (ADB_PORT, ip.rsplit(".", 1)[0] + ".0"))
    found = scan(subnet_hosts(ip))
    if not found:
        raise RehomeError("no device answers on port %d. On the TV: Settings > Device Preferences > Developer "
                          "options > USB debugging: On (switch it off and on again if the port stays closed), and "
                          "keep the TV on the same network as this computer. Or pass --host." % ADB_PORT)
    for i, h in enumerate(found, 1):
        ui.say("  %d) %s" % (i, h))
    if len(found) == 1:
        return found[0]
    if not ui.interactive or ui.yes:
        raise Refused("several devices answer on port %d; pass the TV's address with --host" % ADB_PORT)
    choice = ui.ask("Which one is the TV? [1-%d] " % len(found)).strip()
    if not choice.isdigit() or not 1 <= int(choice) <= len(found):
        raise Refused("no TV chosen")
    return found[int(choice) - 1]


def connect(host: str, ui: Ui, runner: Runner = run_adb, wait: float = 60, sleep=time.sleep,
            clock=time.monotonic) -> Device:
    target = host if ":" in host else "%s:%d" % (host, ADB_PORT)
    if not re.fullmatch(r"[A-Za-z0-9.\-]+:\d{1,5}", target):
        raise Refused("not a TV address: %r" % host)
    rc, out = runner(["adb", "connect", target], timeout=30)
    # "failed to authenticate" means the TV is showing the key prompt: wait for it below.
    if "authenticate" not in out and ("connected" not in out or "cannot" in out or "failed" in out):
        raise RehomeError("adb could not connect to %s (%s). Is USB debugging on, and is the TV on this network?"
                          % (target, out.strip()))
    deadline = clock() + wait
    told = False
    while True:
        rc, out = runner(["adb", "devices"], timeout=30)
        state = ""
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0] == target:
                state = parts[1]
        if state == "device":
            ui.say("  connected to " + target)
            return Device(target, runner)
        if not told:
            ui.say("  Look at the TV: accept \"Allow USB debugging?\" for this computer (tick \"Always allow\").")
            told = True
        if clock() > deadline:
            raise RehomeError("the TV did not accept this computer's key within %d s (state: %s). Run again and "
                              "press OK on the TV's prompt." % (wait, state or "not listed"))
        sleep(1)


# --------------------------------------------------------------------------------------------- command line

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="rehome.py",
        description="Install rehome on a Philips Android TV (PH1M_EA_9970A board): find the TV, back it up, "
                    "disable the cleanup list (never uninstall), install the launcher, make it Home.")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--host", help="the TV's IP address (default: scan the local /24 for port 5555)")
    common.add_argument("--yes", action="store_true", help="do not ask for confirmations")
    sub = p.add_subparsers(dest="command", required=True, metavar="command")

    i = sub.add_parser("install", parents=[common], help="back up, clean up, install rehome and make it Home")
    i.add_argument("--local", action="store_true",
                   help="use the APKs in app/build/outputs and wifi/build/outputs instead of the latest release")
    i.add_argument("--any-board", action="store_true",
                   help="on a board rehome does not know: install the launcher only, without any cleanup")
    i.add_argument("--with-optional", action="store_true", help="also disable cleanup/optional-preloads.txt")
    i.add_argument("--animations", action="store_true", help="set the system animation scale to 0.25")
    i.add_argument("--dry-run", action="store_true", help="show the plan and change nothing")

    sub.add_parser("backup", parents=[common], help="save the TV's state (packages, disabled, Home, animations)")

    c = sub.add_parser("check", parents=[common],
                       help="after a firmware update: is the cleanup and Home still in place?")
    c.add_argument("--fix", action="store_true", help="disable the list again and make rehome Home again")
    c.add_argument("--with-optional", action="store_true", help="include cleanup/optional-preloads.txt")
    c.add_argument("--any-board", action="store_true", help="on an unknown board: check and fix Home only")

    r = sub.add_parser("restore", parents=[common],
                       help="enable everything rehome disabled and give Home back to the stock launcher")
    r.add_argument("--uninstall", action="store_true", help="also uninstall the rehome launcher and companion")
    for s in (i, c, r, sub.choices["backup"]):
        s.set_defaults(local=False, any_board=False, with_optional=False, animations=False, dry_run=False,
                       fix=False, uninstall=False)
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    ui = Ui(yes=args.yes)
    root = backups_root()
    try:
        if args.command == "restore" or (args.command == "install" and not args.dry_run) or \
                (args.command == "check" and args.fix):
            ui.require_consent()
        check_prereqs(ui)
        dev = connect(find_host(args, ui), ui)
        if args.command == "install":
            with tempfile.TemporaryDirectory(prefix="rehome-") as tmp:
                apks = local_apks if args.local else (lambda: download_apks(Path(tmp)))
                install(dev, ui, args, apks, root)
            return 0
        if args.command == "backup":
            backup_command(dev, ui, args, root)
            return 0
        if args.command == "restore":
            restore(dev, ui, args, root)
            return 0
        return check(dev, ui, args, root)
    except Refused as e:
        ui.say("rehome stopped: %s" % e)
        return 2
    except RehomeError as e:
        ui.say("rehome error: %s" % e)
        return 1
    except KeyboardInterrupt:
        ui.say("interrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main())
