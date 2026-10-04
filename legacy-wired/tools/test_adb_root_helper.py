"""Run the real helper in an unprivileged sandbox with fake run-as/ip6tables."""
import contextlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent
TAG = "diplay_" + "a" * 32
OTHER_TAG = "diplay_" + "b" * 32
SH = shutil.which("sh")
if not SH and os.name == "nt":
    candidate = Path("C:/Program Files/Git/bin/bash.exe")
    if candidate.exists():
        SH = str(candidate)


def shell_path(path):
    text = Path(path).as_posix()
    if os.name == "nt" and len(text) > 2 and text[1] == ":":
        return "/" + text[0].lower() + text[2:]
    return text


class Fixture:
    def __init__(self, directory, reject=True, insert_fail=False, delete_fail=False):
        self.directory = Path(directory)
        self.files = self.directory / "files"
        self.files.mkdir()
        self.clock = self.directory / "uptime"
        self.clock.write_text("100.00 20.00\n")
        self.calls = self.directory / "calls"
        identity = self.executable("fake-id", "#!/bin/sh\necho 'uid=0(root) gid=0(root)'\n")
        insert_failure = 'case "$*" in *"-I st_filter_OUTPUT"*) exit 1 ;; esac' if insert_fail else ""
        delete_failure = 'case "$*" in *"-D st_filter_OUTPUT"*) exit 1 ;; esac' if delete_fail else ""
        ip6 = self.executable("fake-ip6tables", f"""#!/bin/sh
case "$*" in
    *"-S st_filter_OUTPUT"*) echo '{"-A st_filter_OUTPUT -j REJECT" if reject else "-N st_filter_OUTPUT"}'; exit 0 ;;
esac
echo "$*" >> '{shell_path(self.calls)}'
{insert_failure}
{delete_failure}
exit 0
""")
        run_as = self.executable("fake-run-as", """#!/bin/sh
shift
case "$1" in *fake-id*) echo 'uid=10032(u0_a32) gid=10032(u0_a32)'; exit 0 ;; esac
exec "$@"
""")
        script = (HERE / "adb-root-ipv6.sh").read_text()
        script = script.replace("/system/bin/run-as", shell_path(run_as))
        script = script.replace("/system/bin/ip6tables", shell_path(ip6))
        script = script.replace("/system/bin/id", shell_path(identity))
        script = script.replace("/system/bin/", "/bin/")
        script = script.replace("/proc/uptime", shell_path(self.clock)).replace("sleep 1", "sleep 0.05")
        self.script = self.directory / "helper.sh"
        self.script.write_text(script)
        self.process = subprocess.Popen([SH, str(self.script), "10032", "60"], cwd=self.directory,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)

    def executable(self, name, body):
        path = self.directory / name
        path.write_text(body)
        path.chmod(0o700)
        return path

    def request(self, action="OPEN", iface="tun12", tag=TAG, uid="10032", expiry="108"):
        temporary = self.files / "request.new"
        temporary.write_text(f"{action} {iface} {tag} {uid} {expiry}\n")
        temporary.replace(self.files / "adb-ipv6-request")

    def status(self):
        path = self.files / "adb-ipv6-status"
        return path.read_text().strip() if path.exists() else ""

    def wait_for(self, state, tag=TAG):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.status().startswith(f"{tag} {state} "):
                return
            if self.process.poll() is not None:
                break
            time.sleep(0.025)
        raise AssertionError(f"Expected {state}, status={self.status()}, exit={self.process.poll()}")

    def rules(self):
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def eof(self):
        self.process.stdin.close()
        self.process.wait(timeout=5)

    def close(self):
        if self.process.poll() is None:
            self.eof()
        self.process.stdout.close()
        self.process.stderr.close()
        self.process.stdin.close()


@unittest.skipUnless(SH, "A POSIX shell is required")
class HelperTest(unittest.TestCase):
    @contextlib.contextmanager
    def fixture(self, **kwargs):
        with tempfile.TemporaryDirectory(prefix="diplay-helper-", dir=os.environ.get("DIPLAY_TEST_TMP")) as directory:
            instance = Fixture(directory, **kwargs)
            try:
                yield instance
            finally:
                instance.close()

    def test_close_removes_only_the_scoped_rule_and_next_lease_can_start(self):
        with self.fixture() as fixture:
            fixture.request()
            fixture.wait_for("READY")
            fixture.request(action="CLOSE")
            fixture.wait_for("REMOVED")
            calls = fixture.rules()
            self.assertEqual(2, len(calls))
            scope = "-o tun12 -m owner --uid-owner 10032 -s fe80::2/128 -d fe80::/64"
            self.assertTrue(all(scope in call and TAG in call and call.endswith("-j RETURN") for call in calls))
            self.assertIn("-I st_filter_OUTPUT 1", calls[0])
            self.assertIn("-D st_filter_OUTPUT", calls[1])
            fixture.request(tag=OTHER_TAG)
            fixture.wait_for("READY", OTHER_TAG)
            fixture.eof()
            self.assertEqual(4, len(fixture.rules()))
            self.assertFalse((fixture.files / "adb-root-helper-lock").exists())

    def test_adb_stdin_eof_removes_rule(self):
        with self.fixture() as fixture:
            fixture.request()
            fixture.wait_for("READY")
            fixture.eof()
            self.assertEqual(2, len(fixture.rules()))
            self.assertIn("-D st_filter_OUTPUT", fixture.rules()[-1])

    def test_app_heartbeat_expiry_removes_rule(self):
        with self.fixture() as fixture:
            fixture.request()
            fixture.wait_for("READY")
            fixture.clock.write_text("111.00 20.00\n")
            fixture.wait_for("REMOVED")
            self.assertEqual(2, len(fixture.rules()))

    def test_hard_deadline_removes_rule(self):
        with self.fixture() as fixture:
            fixture.request()
            fixture.wait_for("READY")
            fixture.clock.write_text("200.00 20.00\n")
            fixture.process.wait(timeout=5)
            self.assertEqual(2, len(fixture.rules()))

    def test_reject_absent_does_not_change_firewall(self):
        with self.fixture(reject=False) as fixture:
            fixture.request()
            fixture.wait_for("NO_REJECT")
            fixture.request(action="CLOSE")
            fixture.wait_for("REMOVED")
            self.assertEqual([], fixture.rules())

    def test_failed_insert_never_reports_ready(self):
        with self.fixture(insert_fail=True) as fixture:
            fixture.request()
            fixture.process.wait(timeout=5)
            output = fixture.process.stdout.read()
            self.assertIn("DIPLAY_HELPER_ERROR_INSERT", output)
            self.assertNotIn("DIPLAY_HELPER_READY", output)
            self.assertEqual(1, len(fixture.rules()))

    def test_failed_delete_does_not_claim_removal(self):
        with self.fixture(delete_fail=True) as fixture:
            fixture.request()
            fixture.wait_for("READY")
            fixture.request(action="CLOSE")
            fixture.process.wait(timeout=5)
            self.assertIn("ERROR_REMOVE", fixture.status())

    def test_malformed_requests_never_become_shell_commands(self):
        cases = [
            {"iface": "tun0;id"}, {"iface": "wlan0"}, {"iface": "tun123456"},
            {"tag": TAG + ";id"}, {"uid": "0"}, {"uid": "10033"},
            {"expiry": "9999999999999999999999999"}, {"expiry": "200"},
            {"action": "RUN"}, {"tag": "diplay_" + "A" * 32},
        ]
        for request in cases:
            with self.subTest(request=request), self.fixture() as fixture:
                fixture.request(**request)
                time.sleep(0.2)
                self.assertEqual([], fixture.rules())
                self.assertEqual("", fixture.status())

    def test_duplicate_helper_cannot_touch_active_lease(self):
        with self.fixture() as fixture:
            fixture.request()
            fixture.wait_for("READY")
            second = subprocess.run([SH, str(fixture.script), "10032", "60"],
                                    cwd=fixture.directory, input="", text=True, capture_output=True, timeout=5)
            self.assertNotEqual(0, second.returncode)
            self.assertIn("ALREADY_RUNNING", second.stdout)
            self.assertEqual(1, len(fixture.rules()))


if __name__ == "__main__":
    unittest.main()
