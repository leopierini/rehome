"""Tests for tools/rehome.py, against a fake TV. Run from the repo root:

    python3 -m unittest tools/test_rehome.py -v

No test talks to a real device: every adb call goes to FakeTV, which records it and answers from its own state.
"""
import contextlib
import io
import os
import re
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rehome  # noqa: E402

# The installer waits a few seconds for rehome to settle before it trusts Home; the fake TV needs no wait.
rehome.HOME_SETTLE_SECONDS = 0

L = "io.github.leopierini.rehome"
W = "io.github.leopierini.rehome.wifi"
STOCK = "com.google.android.tvlauncher"
RECS = "com.google.android.tvrecommendations"
HOME_QUERY = ("cmd package resolve-activity --brief -a android.intent.action.MAIN "
              "-c android.intent.category.HOME")
CHOOSER = "android/com.android.internal.app.ResolverActivity"
RESUMED_QUERY = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"

KEPT = {
    "com.android.vending", "com.google.android.gms", "com.google.android.tv.remote.service",
    "org.droidtv.eum", "org.droidtv.playtv", "com.mediatek.tvinput", "org.droidtv.xtv",
    "com.google.android.katniss", "com.google.android.apps.mediashell", "com.netflix.ninja",
    "com.android.tv.settings",
}


def all_list_packages():
    out = set()
    for name in ("ph1m_ea_9970a.txt", "optional-preloads.txt"):
        out |= {e.package for e in rehome.load_list(rehome.CLEANUP_DIR / name)}
    return out


class FakeTV:
    """A TV that answers the adb commands rehome sends, keeps state, and records every call."""

    APKS = {"rehome.apk": L, "rehome-wifi.apk": W}

    def __init__(self, board="TP1M_EA_9970A", device="PH1M_EA_9970A", sdk="30", serial="FAKE0001",
                 installed=None, disabled=(), tvinput=("com.mediatek.tvinput",)):
        self.props = {
            "ro.product.board": board, "ro.product.device": device, "ro.build.version.sdk": sdk,
            "ro.serialno": serial, "ro.boot.device_name": "55OLED706/12",
            "ro.product.model": "2021/22 Philips UHD Android TV",
            "ro.build.fingerprint": "Philips/PH1M_EA_9970A/PH1M_EA_9970A:11/RTT2.211108.001/21029:user/release-keys",
        }
        if installed is None:
            installed = (all_list_packages() | KEPT) - {"org.droidtv.m7opapp"}
        self.installed = set(installed)
        self.disabled = set(disabled)
        self.tvinput = list(tvinput)
        self.preferred = None
        self.settings = {"global": {"window_animation_scale": "1.0", "transition_animation_scale": "1.0"},
                         "secure": {}}
        self.granted = {}
        self.appops = {}
        self.start_ok = True
        self.top = STOCK + "/.MainActivity"   # the resumed activity
        self.pid = "4242"           # rehome's process
        self.falls_over = None      # "gone": rehome dies after starting; "loop": it dies and comes back
        self.hijack = None          # what HOME resolves to once the stock launcher is gone (None: normal)
        self.blocked = set()        # packages whose install Play Protect blocks
        self.calls = []

    # -- what the tests look at
    def shell_calls(self):
        return [c[4] for c in self.calls if len(c) > 4 and c[3] == "shell"]

    def index(self, pattern):
        for i, cmd in enumerate(self.shell_calls()):
            if re.search(pattern, cmd):
                return i
        return -1

    def home_candidates(self):
        return [p for p in (STOCK, L) if p in self.installed and p not in self.disabled]

    def resolve_home(self):
        cands = self.home_candidates()
        if STOCK in cands:          # the stock launcher's HOME filter has priority 2 on this firmware
            return STOCK + "/.MainActivity"
        if self.hijack:
            return self.hijack
        if self.preferred and self.preferred.split("/")[0] in cands:
            return self.preferred
        if len(cands) == 1:
            return cands[0] + "/.MainActivity"
        return CHOOSER

    def _home_set_changed(self, pkg):
        if pkg in (STOCK, L):
            self.preferred = None

    # -- the adb binary
    def __call__(self, argv, timeout=None):
        self.calls.append(list(argv))
        assert argv[0] == "adb", argv
        args = argv[1:]
        if args[:1] == ["-s"]:
            args = args[2:]
        if args[0] == "install":
            pkg = self.APKS[os.path.basename(args[-1])]
            if pkg in self.blocked:
                return 1, "Performing Streamed Install\nadb: failed to install %s: Failure " \
                          "[INSTALL_FAILED_VERIFICATION_FAILURE: Package Verification Result]\n" % args[-1]
            self.installed.add(pkg)
            self.disabled.discard(pkg)
            self._home_set_changed(pkg)
            return 0, "Performing Streamed Install\nSuccess\n"
        if args[0] == "uninstall":
            self.installed.discard(args[1])
            self._home_set_changed(args[1])
            return 0, "Success\n"
        if args[0] == "shell":
            return self.shell(args[1])
        raise AssertionError("unexpected adb call: %r" % (argv,))

    def shell(self, cmd):
        m = re.fullmatch(r"getprop (\S+)", cmd)
        if m:
            return 0, self.props.get(m.group(1), "") + "\n"
        m = re.fullmatch(r"pm list packages( -[ud])?", cmd)
        if m:
            flag = m.group(1)
            pkgs = self.disabled & self.installed if flag == " -d" else self.installed
            return 0, "".join("package:%s\n" % p for p in sorted(pkgs))
        m = re.fullmatch(r"pm disable-user --user 0 (\S+)", cmd)
        if m:
            pkg = m.group(1)
            if pkg not in self.installed:
                return 1, "Error: java.lang.IllegalArgumentException: Unknown package: %s\n" % pkg
            self.disabled.add(pkg)
            self._home_set_changed(pkg)
            return 0, "Package %s new state: disabled-user\n" % pkg
        m = re.fullmatch(r"pm enable --user 0 (\S+)", cmd)
        if m:
            pkg = m.group(1)
            self.disabled.discard(pkg)
            self._home_set_changed(pkg)
            return 0, "Package %s new state: enabled\n" % pkg
        if cmd == "pm query-services --components -a android.media.tv.TvInputService":
            return 0, "".join("%s/.TvInput\n" % p for p in self.tvinput)
        if cmd == HOME_QUERY:
            return 0, "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n%s\n" % (
                self.resolve_home())
        m = re.fullmatch(re.escape(HOME_QUERY) + r" (\S+)", cmd)
        if m:
            pkg = m.group(1)
            return 0, ("%s/.MainActivity\n" % pkg) if pkg in self.installed else "No activity found\n"
        m = re.fullmatch(r"cmd package set-home-activity --user 0 (\S+)", cmd)
        if m:
            self.preferred = m.group(1)
            return 0, "Success: set home activity to %s\n" % m.group(1)
        if cmd.startswith("am start -W "):
            if not self.start_ok:
                return 0, "Starting: Intent { }\nError: Activity not started, unable to resolve Intent\n"
            self.top = L + "/.MainActivity"
            return 0, "Starting: Intent { }\nStatus: ok\nLaunchState: COLD\nTotalTime: 500\nComplete\n"
        if cmd == RESUMED_QUERY:
            if self.falls_over == "gone":       # crashed: the system shows the stock Home again
                self.top = STOCK + "/.MainActivity"
            rec = "ActivityRecord{9f3c2a1 u0 %s t17}" % self.top
            return 0, "    mResumedActivity: %s\n  topResumedActivity=%s\n" % (rec, rec)
        if cmd == "pidof " + L:
            pid = self.pid
            if self.falls_over == "loop":       # crashed and restarted: a new process each time
                self.pid = str(int(self.pid) + 1)
            return 0, pid + "\n"
        if cmd.startswith("am force-stop "):
            return 0, ""
        m = re.fullmatch(r"settings get (global|secure) (\S+)", cmd)
        if m:
            return 0, self.settings[m.group(1)].get(m.group(2), "null") + "\n"
        m = re.fullmatch(r"settings put (global|secure) (\S+) (\S+)", cmd)
        if m:
            self.settings[m.group(1)][m.group(2)] = m.group(3)
            return 0, ""
        m = re.fullmatch(r"settings delete (global|secure) (\S+)", cmd)
        if m:
            self.settings[m.group(1)].pop(m.group(2), None)
            return 0, "Deleted 1 rows\n"
        m = re.fullmatch(r"pm grant (\S+) (\S+)", cmd)
        if m:
            self.granted.setdefault(m.group(1), set()).add(m.group(2))
            return 0, ""
        m = re.fullmatch(r"dumpsys package (\S+)", cmd)
        if m:
            perms = sorted(self.granted.get(m.group(1), ()))
            return 0, "".join("      %s: granted=true\n" % p for p in perms)
        m = re.fullmatch(r"cmd appops set (\S+) (\S+) (\S+)", cmd)
        if m:
            self.appops[(m.group(1), m.group(2))] = m.group(3)
            return 0, ""
        m = re.fullmatch(r"cmd appops get (\S+) (\S+)", cmd)
        if m:
            return 0, "%s: %s\n" % (m.group(2), self.appops.get((m.group(1), m.group(2)), "default"))
        if cmd == "appops write-settings":
            return 0, ""
        m = re.fullmatch(r"cmd notification allow_listener (\S+)", cmd)
        if m:
            self.settings["secure"]["enabled_notification_listeners"] = m.group(1)
            return 0, ""
        if cmd.startswith("cmd package compile "):
            return 0, "Success\n"
        raise AssertionError("unexpected shell command: %r" % cmd)


class FakeUi(rehome.Ui):
    def __init__(self, yes=True, interactive=False, answers=()):
        self.lines = []
        self.answers = list(answers)
        super().__init__(yes=yes, interactive=interactive, out=self.lines.append, ask=self._ask)

    def _ask(self, prompt):
        self.lines.append(prompt)
        return self.answers.pop(0)

    def text(self):
        return "\n".join(self.lines)


def opts(**kw):
    base = dict(any_board=False, with_optional=False, animations=False, dry_run=False, uninstall=False,
                fix=False)
    base.update(kw)
    return types.SimpleNamespace(**base)


APKS = rehome.Apks(launcher="/nowhere/rehome.apk", companion="/nowhere/rehome-wifi.apk")


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "backups"

    def tearDown(self):
        self.tmp.cleanup()

    def install(self, tv, ui=None, **kw):
        ui = ui or FakeUi()
        dev = rehome.Device("10.0.0.2:5555", runner=tv)
        rehome.install(dev, ui, opts(**kw), APKS, self.root)
        return ui

    def assertNeverUninstalls(self, tv):
        for c in tv.calls:
            joined = " ".join(c)
            self.assertNotIn("pm uninstall", joined)
            self.assertNotRegex(joined, r"\bpm (hide|clear)\b")


class ParseListTest(unittest.TestCase):
    TEXT = (
        "# header comment\n"
        "\n"
        "[First group]\n"
        "org.example.one        # first reason\n"
        "org.example.two  # needs-tvinput: asks for the permission, provides no input\n"
        "# a comment in the middle\n"
        "[Second group]\n"
        "com.google.android.tvlauncher  # after-home: stock home\n"
        "org.example.three\n"
    )

    def test_groups_reasons_comments_and_tags(self):
        entries = rehome.parse_list(self.TEXT)
        self.assertEqual([e.package for e in entries],
                         ["org.example.one", "org.example.two", STOCK, "org.example.three"])
        self.assertEqual([e.group for e in entries], ["First group", "First group", "Second group", "Second group"])
        self.assertEqual(entries[0].reason, "first reason")
        self.assertEqual(entries[0].tags, ())
        self.assertEqual(entries[1].tags, ("needs-tvinput",))
        self.assertTrue(entries[1].needs_tvinput)
        self.assertEqual(entries[1].reason, "asks for the permission, provides no input")
        self.assertTrue(entries[2].after_home)
        self.assertEqual(entries[3].reason, "")

    def test_bad_lines_raise(self):
        for bad in ("[Group]\nnot a package name\n", "[Group]\norg.a.b\norg.a.b\n", "[Group\norg.a.b\n"):
            with self.assertRaises(rehome.ListError, msg=bad):
                rehome.parse_list(bad)

    def test_shipped_lists(self):
        main = rehome.load_list(rehome.CLEANUP_DIR / "ph1m_ea_9970a.txt")
        optional = rehome.load_list(rehome.CLEANUP_DIR / "optional-preloads.txt")
        groups = []
        for e in main:
            if e.group not in groups:
                groups.append(e.group)
        self.assertEqual(groups, ["TP Vision telemetry", "Philips promo and store", "Demo, Alexa and TalkBack",
                                  "Google extras", "Play-Fi and Mimi",
                                  "Google TV launcher and recommendations"])
        self.assertEqual(len(main), 25)
        self.assertTrue(all(e.reason for e in main + optional))
        pk = {e.package: e for e in main}
        self.assertEqual({p for p, e in pk.items() if e.needs_tvinput}, {"org.droidtv.gel", "org.droidtv.usagelogger"})
        self.assertEqual([e.package for e in main if e.after_home], [STOCK, RECS])
        self.assertEqual([e.package for e in optional], ["tv.wuaki.apptv", "com.ted.android.tv"])
        # the apps the owner downloaded himself are not in either list
        for own in ("com.stevenclift.tvnews", "com.accuweather.android", "com.ilmeteo.android.ilmeteosmarttv",
                    "yo.app.free", "com.macropinch.swan", "com.macropinch.axe", "com.tcl.browser",
                    "com.internet.tvbrowser", "com.samabox.dreamy"):
            self.assertNotIn(own, all_list_packages())
        rehome.check_protected(main + optional)  # does not raise


class BoardTest(unittest.TestCase):
    def test_accepts_ph1m(self):
        self.assertTrue(rehome.board_known({"ro.product.board": "PH1M_EA_9970A"}))
        # what the 55OLED706 really reports
        self.assertTrue(rehome.board_known({"ro.product.board": "TP1M_EA_9970A",
                                            "ro.product.device": "PH1M_EA_9970A"}))
        self.assertEqual(rehome.board_verdict({"ro.product.device": "PH1M_EA_9970A"}, any_board=False), "known")

    def test_refuses_other_board_without_flag(self):
        props = {"ro.product.board": "PH7M_EU_5596", "ro.product.device": "PH7M_EU_5596"}
        self.assertFalse(rehome.board_known(props))
        with self.assertRaises(rehome.Refused):
            rehome.board_verdict(props, any_board=False)
        self.assertEqual(rehome.board_verdict(props, any_board=True), "unknown")


class PlanTest(unittest.TestCase):
    def entries(self, *pkgs, group="G"):
        return [rehome.Entry(p, "why", group, ()) for p in pkgs]

    def test_skips_not_installed_and_already_disabled(self):
        es = self.entries("a.b.one", "a.b.two", "a.b.three")
        plan = rehome.plan_disable(es, installed={"a.b.one", "a.b.two"}, disabled={"a.b.two"})
        self.assertEqual([e.package for e in plan.now], ["a.b.one"])
        skipped = {e.package: why for e, why in plan.skipped}
        self.assertIn("not installed", skipped["a.b.three"])
        self.assertIn("already disabled", skipped["a.b.two"])

    def test_tv_input_providers_are_left_alone(self):
        es = self.entries("a.b.input", "a.b.other")
        plan = rehome.plan_disable(es, installed={"a.b.input", "a.b.other"}, disabled=set(),
                                   tvinput_providers={"a.b.input"})
        self.assertEqual([e.package for e in plan.now], ["a.b.other"])
        self.assertIn("TV input", dict((e.package, w) for e, w in plan.skipped)["a.b.input"])

    def test_after_home_entries_wait_and_stock_launcher_goes_first(self):
        es = [rehome.Entry(RECS, "r", "G", ("after-home",)), rehome.Entry(STOCK, "s", "G", ()),
              rehome.Entry("a.b.c", "x", "G", ())]
        plan = rehome.plan_disable(es, installed={RECS, STOCK, "a.b.c"}, disabled=set())
        self.assertEqual([e.package for e in plan.now], ["a.b.c"])
        self.assertEqual([e.package for e in plan.after_home], [STOCK, RECS])

    def test_protected_package_in_a_list_raises(self):
        for pkg in ("com.android.vending", "com.google.android.tv.remote.service", "org.droidtv.eum",
                    "org.droidtv.playtv", "com.mediatek.tvinput", "com.google.android.katniss",
                    "org.droidtv.dlna", "org.droidtv.ipcontrol", "com.google.android.apps.mediashell",
                    "org.droidtv.oad", "com.easy.oad", "com.android.tv.settings", L, W,
                    "com.netflix.ninja", "com.android.providers.tv", "com.google.android.permissioncontroller",
                    "com.google.android.overlay.modules.ext.services", "android"):
            es = self.entries("a.b.fine", pkg)
            with self.assertRaises(rehome.ProtectedPackageError, msg=pkg):
                rehome.plan_disable(es, installed={"a.b.fine", pkg}, disabled=set())
        # even when the package is not installed, the list itself is refused
        with self.assertRaises(rehome.ProtectedPackageError):
            rehome.plan_disable(self.entries("com.android.vending"), installed=set(), disabled=set())


class InstallTest(Base):
    def test_full_install_order_and_grants(self):
        tv = FakeTV()
        ui = self.install(tv)
        calls = tv.shell_calls()
        set_home = tv.index(r"^cmd package set-home-activity --user 0 %s/\.MainActivity$" % re.escape(L))
        off_stock = tv.index(r"^pm disable-user --user 0 %s$" % re.escape(STOCK))
        off_recs = tv.index(r"^pm disable-user --user 0 %s$" % re.escape(RECS))
        started = tv.index(r"^am start -W .*-n %s/\.MainActivity$" % re.escape(L))
        self.assertGreaterEqual(set_home, 0)
        self.assertGreater(off_stock, set_home, "the stock launcher must go only after set-home-activity")
        self.assertGreater(off_recs, off_stock)
        self.assertLess(started, set_home)
        # the rest of the list went first, the TV-side cleanup before the APKs
        first_disable = tv.index(r"^pm disable-user ")
        self.assertEqual(calls[first_disable], "pm disable-user --user 0 org.droidtv.candeebug")
        installs = [i for i, c in enumerate(tv.calls) if c[3:4] == ["install"]]
        self.assertEqual(len(installs), 2)
        self.assertEqual(tv.resolve_home(), L + "/.MainActivity")
        self.assertIn(STOCK, tv.disabled)
        self.assertNotIn("org.droidtv.m7opapp", tv.disabled)  # not installed: skipped
        self.assertIn("org.droidtv.m7opapp", ui.text())
        # optional group only on request
        self.assertNotIn("tv.wuaki.apptv", tv.disabled)
        # nothing protected touched
        self.assertFalse(KEPT & tv.disabled)
        # grants, as the original installer did them
        self.assertEqual(tv.granted[W], {"android.permission.ACCESS_COARSE_LOCATION",
                                         "android.permission.ACCESS_FINE_LOCATION",
                                         "android.permission.ACCESS_BACKGROUND_LOCATION"})
        self.assertEqual(tv.granted[L], {"android.permission.ACCESS_FINE_LOCATION",
                                         "android.permission.READ_TV_LISTINGS"})
        self.assertEqual(tv.appops[(L, "GET_USAGE_STATS")], "allow")
        self.assertIn("appops write-settings", calls)
        self.assertEqual(tv.settings["secure"]["enabled_notification_listeners"], L + "/.MediaListener")
        # animations untouched without --animations
        self.assertEqual(tv.settings["global"]["window_animation_scale"], "1.0")
        self.assertIn("Install anyway", ui.text())
        self.assertNeverUninstalls(tv)

    def test_optional_and_animations_on_request(self):
        tv = FakeTV()
        self.install(tv, with_optional=True, animations=True)
        self.assertIn("tv.wuaki.apptv", tv.disabled)
        self.assertIn("com.ted.android.tv", tv.disabled)
        for key in rehome.ANIMATION_KEYS:
            self.assertEqual(tv.settings["global"][key], "0.25")

    def test_interactive_answers(self):
        tv = FakeTV()
        # optional group? yes; apply? yes; animations? no
        ui = FakeUi(yes=False, interactive=True, answers=["y", "y", "n"])
        self.install(tv, ui=ui)
        self.assertIn("tv.wuaki.apptv", tv.disabled)
        self.assertEqual(tv.settings["global"]["window_animation_scale"], "1.0")

    def test_declining_changes_nothing(self):
        tv = FakeTV()
        ui = FakeUi(yes=False, interactive=True, answers=["n", "n"])
        with self.assertRaises(rehome.Refused):
            self.install(tv, ui=ui)
        self.assertEqual(tv.index(r"^pm disable-user"), -1)
        self.assertFalse([c for c in tv.calls if c[3:4] == ["install"]])

    def test_no_terminal_and_no_yes_refuses_before_any_change(self):
        tv = FakeTV()
        with self.assertRaises(rehome.Refused):
            self.install(tv, ui=FakeUi(yes=False, interactive=False))
        self.assertEqual(tv.index(r"^(pm (disable|enable|grant)|cmd package set-home|settings put)"), -1)
        self.assertFalse([c for c in tv.calls if c[3:4] == ["install"]])

    def test_dry_run_changes_nothing(self):
        tv = FakeTV()
        ui = self.install(tv, dry_run=True)
        self.assertEqual(tv.index(r"^(pm (disable|enable|grant)|cmd package set-home|settings put)"), -1)
        self.assertIn("org.droidtv.candeebug", ui.text())

    def test_unknown_board_is_refused(self):
        tv = FakeTV(board="PH7M_EU_5596", device="PH7M_EU_5596")
        with self.assertRaises(rehome.Refused):
            self.install(tv)
        self.assertEqual(tv.index(r"^pm disable-user"), -1)
        self.assertFalse([c for c in tv.calls if c[3:4] == ["install"]])

    def test_any_board_installs_launcher_only(self):
        tv = FakeTV(board="PH7M_EU_5596", device="PH7M_EU_5596")
        self.install(tv, any_board=True)
        self.assertEqual(tv.index(r"^pm disable-user"), -1)
        self.assertIn(L, tv.installed)
        self.assertGreaterEqual(tv.index(r"^cmd package set-home-activity --user 0 %s/" % re.escape(L)), 0)

    def test_launcher_that_does_not_start_keeps_stock_home(self):
        tv = FakeTV()
        tv.start_ok = False
        with self.assertRaises(rehome.RehomeError):
            self.install(tv)
        self.assertNotIn(STOCK, tv.disabled)
        self.assertNotIn(RECS, tv.disabled)
        self.assertEqual(tv.resolve_home(), STOCK + "/.MainActivity")

    def test_home_is_confirmed_on_screen_before_the_stock_launcher_goes(self):
        tv = FakeTV()
        with mock.patch.object(rehome, "HOME_SETTLE_SECONDS", 3), mock.patch.object(rehome.time, "sleep") as slept:
            self.install(tv)
        slept.assert_any_call(3)
        set_home = tv.index(r"^cmd package set-home-activity --user 0 %s/" % re.escape(L))
        looked = tv.index(r"^" + re.escape(RESUMED_QUERY) + r"$")
        off_stock = tv.index(r"^pm disable-user --user 0 %s$" % re.escape(STOCK))
        self.assertGreater(looked, set_home)
        self.assertLess(looked, off_stock, "rehome must be seen on screen before the stock launcher goes")
        self.assertIn(STOCK, tv.disabled)

    def test_launcher_that_falls_over_after_starting_keeps_stock_home(self):
        for how in ("gone", "loop"):
            tv = FakeTV()
            tv.falls_over = how
            with self.assertRaises(rehome.RehomeError, msg=how) as cm:
                self.install(tv)
            msg = str(cm.exception)
            self.assertIn("did not stay", msg)
            self.assertIn("stock launcher", msg)
            self.assertIn("restore", msg)
            self.assertNotIn(STOCK, tv.disabled, how)
            self.assertNotIn(RECS, tv.disabled, how)
            self.assertEqual(tv.index(r"^pm disable-user --user 0 (%s|%s)$" % (re.escape(STOCK), re.escape(RECS))),
                             -1, how)
            # Home was given back to the stock launcher after rehome had been made Home.
            homes = [c for c in tv.shell_calls() if c.startswith("cmd package set-home-activity")]
            self.assertEqual(homes[-1], "cmd package set-home-activity --user 0 %s/.MainActivity" % STOCK, how)
            self.assertEqual(tv.resolve_home(), STOCK + "/.MainActivity")
            # the applied record still lists what step 6 disabled, so restore can undo it
            applied = (rehome.earliest_backup(self.root, "FAKE0001") / "applied.txt").read_text()
            self.assertIn("org.droidtv.candeebug", applied)
            self.assertNeverUninstalls(tv)

    def test_check_fix_also_requires_rehome_on_screen(self):
        tv = FakeTV()
        self.install(tv)
        tv.disabled.discard(STOCK)
        tv.preferred = None
        tv.falls_over = "gone"
        dev = rehome.Device("10.0.0.2:5555", runner=tv)
        with self.assertRaises(rehome.RehomeError):
            rehome.check(dev, FakeUi(), opts(fix=True), self.root)
        self.assertNotIn(STOCK, tv.disabled)

    def test_connection_lost_during_handover_prints_the_way_back(self):
        tv = FakeTV()
        real = tv.shell

        def flaky(cmd):
            if cmd == HOME_QUERY and STOCK in tv.disabled:
                return 1, "error: closed\n"
            return real(cmd)
        tv.shell = flaky
        with self.assertRaises(rehome.ConnectionLost) as cm:
            self.install(tv)
        self.assertIn("pm enable --user 0 %s" % STOCK, str(cm.exception))
        self.assertIn("set-home-activity --user 0 %s/.MainActivity" % STOCK, str(cm.exception))

    def test_missing_apks_stop_before_any_change(self):
        for local in (False, True):
            tv = FakeTV()

            def no_apks():
                raise rehome.RehomeError("could not download rehome.apk")
            ui = FakeUi()
            with self.assertRaises(rehome.RehomeError) as cm:
                rehome.install(rehome.Device("10.0.0.2:5555", runner=tv), ui, opts(local=local), no_apks, self.root)
            self.assertIn("Nothing was changed on the TV", str(cm.exception))
            changes = (r"^(pm (disable|enable|grant)|cmd package (set-home|compile)|cmd appops set|"
                       r"cmd notification|am (start|force-stop)|settings (put|delete)|appops write)")
            self.assertEqual(tv.index(changes), -1)
            self.assertTrue(tv.calls)  # it did talk to the TV (read-only) before stopping
            self.assertFalse([c for c in tv.calls if c[3:4] in (["install"], ["uninstall"])])
            self.assertNotIn("Apply these changes", ui.text())

    def test_local_apks_missing_is_a_clear_error(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(rehome.RehomeError) as cm:
                rehome.local_apks(Path(d))
            self.assertIn("assembleRelease", str(cm.exception))

    def test_home_landing_elsewhere_gives_home_back_to_stock(self):
        for elsewhere in (CHOOSER, "org.example.otherhome/.Home"):
            tv = FakeTV()
            tv.hijack = elsewhere
            with self.assertRaises(rehome.RehomeError) as cm:
                self.install(tv)
            self.assertIn(elsewhere, str(cm.exception))
            off_stock = tv.index(r"^pm disable-user --user 0 %s$" % re.escape(STOCK))
            on_recs = tv.index(r"^pm enable --user 0 %s$" % re.escape(RECS))
            on_stock = tv.index(r"^pm enable --user 0 %s$" % re.escape(STOCK))
            back_home = max(i for i, c in enumerate(tv.shell_calls())
                            if c == "cmd package set-home-activity --user 0 %s/.MainActivity" % STOCK)
            self.assertGreater(on_recs, off_stock)
            self.assertGreater(on_stock, on_recs)       # undone in reverse order
            self.assertGreater(back_home, on_stock)
            self.assertNotIn(STOCK, tv.disabled)
            self.assertNotIn(RECS, tv.disabled)
            self.assertEqual(tv.resolve_home(), STOCK + "/.MainActivity")
            self.assertNeverUninstalls(tv)

    def test_play_protect_block_says_install_anyway_then_install_again(self):
        tv = FakeTV()
        tv.blocked.add(W)
        ui = self.install(tv)
        self.assertNotIn(W, tv.installed)
        self.assertNotIn(W, tv.granted)
        out = ui.text()
        warning = next(line for line in ui.lines if "Wi-Fi companion did not install" in line)
        self.assertIn("Install anyway", warning)
        self.assertIn("./install.sh", warning)
        self.assertNotIn("check --fix", warning)
        play_protect = next(line for line in out.splitlines() if "Play Protect:" in line)
        manual = out[out.index(play_protect):out.index("2. OLED")]
        self.assertIn("./install.sh", manual)
        self.assertNotIn("check --fix", manual)
        # The person pressed "Install anyway"; the second run installs the helper and grants it, and changes
        # nothing else that the first run already did.
        tv.blocked.clear()
        disabled = set(tv.disabled)
        tv.calls.clear()
        self.install(tv)
        self.assertIn(W, tv.installed)
        self.assertEqual(tv.granted[W], {"android.permission.ACCESS_COARSE_LOCATION",
                                         "android.permission.ACCESS_FINE_LOCATION",
                                         "android.permission.ACCESS_BACKGROUND_LOCATION"})
        self.assertEqual(tv.disabled, disabled)
        self.assertEqual(tv.index(r"^pm disable-user"), -1)
        self.assertEqual(tv.resolve_home(), L + "/.MainActivity")
        self.assertNeverUninstalls(tv)

    def test_old_android_is_refused(self):
        tv = FakeTV(sdk="25")
        with self.assertRaises(rehome.Refused):
            self.install(tv)
        self.assertFalse([c for c in tv.calls if c[3:4] == ["install"]])


class BackupTest(Base):
    def test_backup_contents(self):
        tv = FakeTV()
        tv.installed.add("org.droidtv.aurora")
        tv.disabled.add("org.droidtv.aurora")
        path = rehome.make_backup(rehome.Device("x:5555", runner=tv), self.root)
        self.assertTrue(path.name.startswith("FAKE0001-"))
        self.assertIn("package:org.droidtv.aurora", (path / "disabled.txt").read_text())
        self.assertIn("package:com.android.vending", (path / "packages.txt").read_text())
        self.assertIn(STOCK + "/.MainActivity", (path / "home.txt").read_text())
        anim = (path / "animations.txt").read_text()
        self.assertIn("window_animation_scale=1.0", anim)
        self.assertIn("animator_duration_scale=null", anim)

    def test_second_backup_never_overwrites_the_first(self):
        tv = FakeTV()
        dev = rehome.Device("x:5555", runner=tv)
        stamp = rehome.datetime.datetime(2026, 1, 2, 3, 4, 5)
        first = rehome.make_backup(dev, self.root, now=stamp)
        first_disabled = (first / "disabled.txt").read_text()
        tv.disabled.add("org.droidtv.candeebug")
        second = rehome.make_backup(dev, self.root, now=stamp)  # same second
        third = rehome.make_backup(dev, self.root, now=rehome.datetime.datetime(2026, 1, 1, 0, 0, 0))
        self.assertEqual(len({first, second, third}), 3)
        self.assertEqual((first / "disabled.txt").read_text(), first_disabled)
        self.assertEqual(rehome.earliest_backup(self.root, "FAKE0001"), third)
        self.assertEqual(rehome.list_backups(self.root, "FAKE0001"), [third, first, second])
        self.assertIsNone(rehome.earliest_backup(self.root, "OTHER"))


class RestoreTest(Base):
    def restore(self, tv, ui=None, **kw):
        ui = ui or FakeUi()
        rehome.restore(rehome.Device("10.0.0.2:5555", runner=tv), ui, opts(**kw), self.root)
        return ui

    def test_reenables_exactly_what_rehome_disabled(self):
        tv = FakeTV()
        tv.installed |= {"org.droidtv.aurora", "a.user.choice"}
        tv.disabled |= {"org.droidtv.aurora"}  # disabled from the factory
        self.install(tv, with_optional=True, animations=True)
        # a later backup (taken with everything already disabled) must not become the restore source
        rehome.make_backup(rehome.Device("x:5555", runner=tv), self.root)
        before = set(tv.disabled)
        tv.calls.clear()
        self.restore(tv)
        enabled = {m.group(1) for m in (re.fullmatch(r"pm enable --user 0 (\S+)", c) for c in tv.shell_calls())
                   if m}
        self.assertEqual(enabled, before - {"org.droidtv.aurora"})
        self.assertEqual(tv.disabled, {"org.droidtv.aurora"})
        self.assertEqual(tv.preferred, STOCK + "/.MainActivity")
        self.assertEqual(tv.resolve_home(), STOCK + "/.MainActivity")
        # stock launcher enabled last, after what it depends on
        self.assertGreater(tv.index(r"^pm enable --user 0 %s$" % re.escape(STOCK)),
                           tv.index(r"^pm enable --user 0 %s$" % re.escape(RECS)))
        self.assertEqual(tv.settings["global"]["window_animation_scale"], "1.0")
        self.assertNotIn("animator_duration_scale", tv.settings["global"])
        self.assertIn(L, tv.installed)  # kept without --uninstall
        self.assertNeverUninstalls(tv)

    def test_leaves_alone_packages_that_came_after_the_backup(self):
        tv = FakeTV()
        self.install(tv)
        # installed and disabled by the owner after rehome's backup: not rehome's to enable
        tv.installed.add("a.later.app")
        tv.disabled.add("a.later.app")
        ui = self.restore(tv)
        self.assertIn("a.later.app", tv.disabled)
        self.assertEqual(tv.index(r"^pm enable --user 0 a\.later\.app$"), -1)
        self.assertNotIn("a.later.app", ui.text())
        self.assertNotIn(STOCK, tv.disabled)

    def test_applied_list_counts_even_if_missing_from_the_package_list(self):
        tv = FakeTV()
        self.install(tv)
        first = rehome.earliest_backup(self.root, "FAKE0001")
        packages = first / "packages.txt"
        packages.write_text(packages.read_text().replace("package:org.droidtv.candeebug\n", ""))
        self.restore(tv)
        self.assertNotIn("org.droidtv.candeebug", tv.disabled)

    def test_reenables_a_list_package_that_check_fix_disabled_after_the_backup(self):
        tv = FakeTV()
        self.install(tv)
        first = rehome.earliest_backup(self.root, "FAKE0001")
        dev = rehome.Device("10.0.0.2:5555", runner=tv)
        # a firmware update adds a list package that was not on the TV at the first backup; check --fix disables it
        self.assertNotIn("package:org.droidtv.m7opapp", (first / "packages.txt").read_text())
        tv.installed.add("org.droidtv.m7opapp")
        self.assertEqual(rehome.check(dev, FakeUi(), opts(fix=True), self.root), 0)
        self.assertIn("org.droidtv.m7opapp", tv.disabled)
        self.assertEqual(rehome.earliest_backup(self.root, "FAKE0001"), first)  # restore still starts from it
        self.restore(tv)
        self.assertNotIn("org.droidtv.m7opapp", tv.disabled)
        self.assertEqual(tv.disabled, set())

    def test_uninstall_rehome_apps_on_request(self):
        tv = FakeTV()
        self.install(tv)
        self.restore(tv, uninstall=True)
        self.assertNotIn(L, tv.installed)
        self.assertNotIn(W, tv.installed)
        uninstalled = [c[4] for c in tv.calls if c[3:4] == ["uninstall"]]
        self.assertEqual(sorted(uninstalled), sorted([L, W]))

    def test_without_backup_refuses(self):
        tv = FakeTV()
        with self.assertRaises(rehome.Refused):
            self.restore(tv)
        self.assertEqual(tv.index(r"^pm enable"), -1)


class CheckTest(Base):
    def test_check_reports_and_fix_redisables(self):
        tv = FakeTV()
        self.install(tv)
        dev = rehome.Device("10.0.0.2:5555", runner=tv)
        self.assertEqual(rehome.check(dev, FakeUi(), opts(), self.root), 0)
        # a firmware update brings two packages and the stock launcher back
        tv.disabled -= {"org.droidtv.candeebug", "com.phorus.playfi.tv", STOCK}
        tv.preferred = None
        ui = FakeUi()
        self.assertEqual(rehome.check(dev, ui, opts(), self.root), 1)
        self.assertIn("org.droidtv.candeebug", ui.text())
        self.assertNotIn("org.droidtv.candeebug", tv.disabled)  # check alone changes nothing
        tv.calls.clear()
        self.assertEqual(rehome.check(dev, FakeUi(), opts(fix=True), self.root), 0)
        self.assertTrue({"org.droidtv.candeebug", "com.phorus.playfi.tv", STOCK} <= tv.disabled)
        self.assertGreater(tv.index(r"^pm disable-user --user 0 %s$" % re.escape(STOCK)),
                           tv.index(r"^cmd package set-home-activity --user 0 %s/" % re.escape(L)))
        self.assertEqual(tv.resolve_home(), L + "/.MainActivity")
        self.assertNeverUninstalls(tv)

    def test_list_entries_that_provide_a_tv_input_do_not_count_as_enabled_again(self):
        # On this set a list entry provides a TV input, so install left it alone: check must still say all is well.
        tv = FakeTV(tvinput=("com.mediatek.tvinput", "org.droidtv.gel"))
        self.install(tv)
        self.assertNotIn("org.droidtv.gel", tv.disabled)
        dev = rehome.Device("10.0.0.2:5555", runner=tv)
        ui = FakeUi()
        self.assertEqual(rehome.check(dev, ui, opts(), self.root), 0)
        self.assertNotIn("enabled again: org.droidtv.gel", ui.text())
        self.assertEqual(rehome.check(dev, FakeUi(), opts(fix=True), self.root), 0)
        self.assertNotIn("org.droidtv.gel", tv.disabled)

    def test_fix_reapplies_optional_entries_that_were_applied(self):
        tv = FakeTV()
        self.install(tv, with_optional=True)
        tv.disabled.discard("tv.wuaki.apptv")
        rehome.check(rehome.Device("10.0.0.2:5555", runner=tv), FakeUi(), opts(fix=True), self.root)
        self.assertIn("tv.wuaki.apptv", tv.disabled)


class NetworkTest(unittest.TestCase):
    def test_scan_lists_open_hosts(self):
        open_hosts = {"192.168.1.50", "192.168.1.7"}

        def fake_connect(addr, timeout):
            self.assertEqual(timeout, 0.3)
            if addr[0] in open_hosts and addr[1] == 5555:
                return types.SimpleNamespace(close=lambda: None)
            raise OSError("refused")

        hosts = ["192.168.1.%d" % i for i in range(1, 255)]
        self.assertEqual(rehome.scan(hosts, connect=fake_connect), ["192.168.1.7", "192.168.1.50"])

    def test_subnet_hosts(self):
        hosts = rehome.subnet_hosts("192.168.1.20")
        self.assertEqual(len(hosts), 253)
        self.assertNotIn("192.168.1.20", hosts)
        self.assertEqual(hosts[0], "192.168.1.1")

    def test_connect_waits_for_the_key(self):
        states = iter(["unauthorized", "unauthorized", "device"])
        calls = []

        def runner(argv, timeout=None):
            calls.append(argv)
            if argv[1] == "connect":
                return 0, "connected to 192.168.1.50:5555\n"
            if argv[1] == "devices":
                return 0, "List of devices attached\n192.168.1.50:5555\t%s\n\n" % next(states)
            raise AssertionError(argv)

        ui = FakeUi()
        dev = rehome.connect("192.168.1.50", ui, runner=runner, sleep=lambda s: None)
        self.assertEqual(dev.serial, "192.168.1.50:5555")
        self.assertIn("accept", ui.text().lower())

    def test_connect_waits_when_adb_says_failed_to_authenticate(self):
        states = iter(["unauthorized", "device"])

        def runner(argv, timeout=None):
            if argv[1] == "connect":
                return 1, "failed to authenticate to 192.168.1.50:5555\n"
            return 0, "List of devices attached\n192.168.1.50:5555\t%s\n" % next(states)

        dev = rehome.connect("192.168.1.50", FakeUi(), runner=runner, sleep=lambda s: None)
        self.assertEqual(dev.serial, "192.168.1.50:5555")

    def test_connect_refuses_bad_address_and_reports_failure(self):
        with self.assertRaises(rehome.Refused):
            rehome.connect("1.2.3.4; reboot", FakeUi(), runner=lambda argv, timeout=None: (0, ""))
        with self.assertRaises(rehome.RehomeError):
            rehome.connect("192.168.1.50", FakeUi(),
                           runner=lambda argv, timeout=None: (1, "failed to connect to 192.168.1.50:5555"))

    def test_connect_gives_up(self):
        clock = iter(range(0, 1000, 5))

        def runner(argv, timeout=None):
            if argv[1] == "connect":
                return 0, "connected to 192.168.1.50:5555\n"
            return 0, "List of devices attached\n192.168.1.50:5555\tunauthorized\n"

        with self.assertRaises(rehome.RehomeError):
            rehome.connect("192.168.1.50", FakeUi(), runner=runner, sleep=lambda s: None,
                           clock=lambda: next(clock))


class CliTest(unittest.TestCase):
    def test_help(self):
        buf = io.StringIO()
        with self.assertRaises(SystemExit) as cm, contextlib.redirect_stdout(buf):
            rehome.main(["--help"])
        self.assertEqual(cm.exception.code, 0)
        for cmd in ("install", "backup", "check", "restore"):
            self.assertIn(cmd, buf.getvalue())

    def test_subcommands_parse(self):
        p = rehome.build_parser()
        a = p.parse_args(["install", "--host", "192.168.1.50", "--local", "--yes", "--with-optional",
                          "--animations", "--any-board"])
        self.assertEqual((a.command, a.host, a.local, a.yes, a.with_optional, a.animations, a.any_board),
                         ("install", "192.168.1.50", True, True, True, True, True))
        self.assertTrue(p.parse_args(["check", "--fix"]).fix)
        self.assertEqual(p.parse_args(["restore"]).command, "restore")
        self.assertEqual(p.parse_args(["backup"]).command, "backup")


if __name__ == "__main__":
    unittest.main()
