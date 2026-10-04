"""Exercise the PowerShell launcher with actual processes and a fake ADB CLI."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
PWSH = shutil.which("pwsh") or os.environ.get("DIPLAY_TEST_PWSH")


@unittest.skipUnless(PWSH, "PowerShell 7 is required")
class LauncherTest(unittest.TestCase):
    def launch(self, mode):
        with tempfile.TemporaryDirectory(prefix="diplay-launcher-", dir=os.environ.get("DIPLAY_TEST_TMP")) as directory:
            root = Path(directory)
            fake = root / "fake_adb.py"
            calls = root / "calls.jsonl"
            fake.write_text("""import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["DIPLAY_FAKE_CALLS"], "a") as log:
    log.write(json.dumps(args) + "\\n")
mode = os.environ["DIPLAY_FAKE_MODE"]
if args[:1] == ["-s"]:
    assert args[1] == "test-device"
    args = args[2:]
if args == ["get-state"]:
    print("device")
elif args == ["shell", "getprop", "ro.build.version.sdk"]:
    print("19")
elif args == ["shell", "run-as", "com.shihab.diplay.legacy", "/system/bin/id"]:
    if mode == "release":
        print("run-as: Package is not debuggable")
        sys.exit(1)
    print("uid=10032(u0_a32) gid=10032(u0_a32)")
elif args == ["shell", "su", "0", "/system/bin/id"]:
    if mode in ("denied", "command"):
        sys.exit(1)
    print("uid=0(root) gid=0(root)")
elif args == ["shell", "su", "-c", "'/system/bin/id'"]:
    if mode == "denied":
        sys.exit(1)
    print("uid=0(root) gid=0(root)")
elif args == ["shell", "run-as", "com.shihab.diplay.legacy", "/system/bin/sh", "-c", "'cat > files/adb-root-ipv6.sh'"]:
    script = sys.stdin.read()
    assert "pkg=com.shihab.diplay.legacy" in script
    assert "trap cleanup 0" in script
    assert "\\r" not in script
elif args[:2] == ["shell", "-T"]:
    if mode == "command":
        assert args == ["shell", "-T", "su", "-c", "'/system/bin/sh /data/data/com.shihab.diplay.legacy/files/adb-root-ipv6.sh 10032 120'"]
    else:
        assert args == ["shell", "-T", "su", "0", "/system/bin/sh", "/data/data/com.shihab.diplay.legacy/files/adb-root-ipv6.sh", "10032", "120"]
    print("DIPLAY_HELPER_WAITING_FOR_APP", flush=True)
    print("DIPLAY_HELPER_READY", flush=True)
    print("DIPLAY_HELPER_REMOVED", flush=True)
else:
    raise AssertionError(args)
""")
            def quote(text):
                return "'" + str(text).replace("'", "''") + "'"
            # Keep the production ArgumentList/read/write/wait code intact.
            # Only substitute python + fake CLI for the adb executable.
            source = (HERE / "Start-LegacyIpv6Helper.ps1").read_text()
            source = source.replace("$info.FileName = $AdbPath",
                                    "$info.FileName = " + quote(sys.executable)
                                    + "\n    $info.ArgumentList.Add(" + quote(fake) + ")")
            launcher = root / "launcher.ps1"
            launcher.write_text(source)
            shutil.copyfile(HERE / "adb-root-ipv6.sh", root / "adb-root-ipv6.sh")
            environment = dict(os.environ, DIPLAY_FAKE_CALLS=str(calls), DIPLAY_FAKE_MODE=mode)
            result = subprocess.run([PWSH, "-NoProfile", "-File", str(launcher),
                                     "-Serial", "test-device", "-MaxMinutes", "2"],
                                    env=environment, capture_output=True, text=True, encoding="utf-8", timeout=30)
            commands = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            return result, commands

    def test_positional_root_launch_preserves_exact_args_and_uploads_script(self):
        result, commands = self.launch("positional")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Temporary helper: UID=10032", result.stdout)
        self.assertIn("DIPLAY_HELPER_REMOVED", result.stdout)
        self.assertEqual(6, len(commands))

    def test_command_style_root_fallback_preserves_remote_shell_quoting(self):
        result, commands = self.launch("command")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(7, len(commands))

    def test_denied_shell_root_does_not_install_or_launch_helper(self):
        result, commands = self.launch("denied")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not grant ADB shell Root", result.stderr)
        self.assertEqual(5, len(commands))

    def test_release_app_does_not_attempt_root_or_upload(self):
        result, commands = self.launch("release")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot run-as", result.stderr)
        self.assertEqual(3, len(commands))


if __name__ == "__main__":
    unittest.main()
