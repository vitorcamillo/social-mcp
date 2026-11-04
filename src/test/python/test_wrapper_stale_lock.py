"""Wrapper stale-lock: executa o wrapper real em ambiente isolado (offline)."""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
WRAPPER = REPO_ROOT / "bin" / "social-mcp"
JAR_REL = Path("target") / "social-mcp-0.1.0.jar"
SUBPROCESS_TIMEOUT = 10


def make_fake_bin(fake_dir: Path) -> None:
    java = fake_dir / "java"
    java.write_text(
        "#!/usr/bin/env bash\n"
        'printf \'%s\' "$JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS"\n',
        encoding="utf-8",
    )
    xdpyinfo = fake_dir / "xdpyinfo"
    xdpyinfo.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    for p in (java, xdpyinfo):
        p.chmod(p.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def run_wrapper(env_override: dict | None = None) -> str:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        repo = tmp_path / "repo"
        (repo / "bin").mkdir(parents=True)
        (repo / "target").mkdir(parents=True)
        dest = repo / "bin" / "social-mcp"
        shutil.copy2(WRAPPER, dest)
        dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        (repo / JAR_REL).write_bytes(b"")

        fake_dir = tmp_path / "fakebin"
        fake_dir.mkdir()
        make_fake_bin(fake_dir)

        fake_home = tmp_path / "home"
        fake_home.mkdir()
        mcp_home = tmp_path / "mcp-home"
        mcp_home.mkdir()

        env = {
            "PATH": str(fake_dir) + os.pathsep + "/usr/bin:/bin",
            "HOME": str(fake_home),
            "SOCIAL_MCP_HOME": str(mcp_home),
            "DISPLAY": ":0",
        }
        env.pop("JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS", None)
        if env_override:
            env.update(env_override)

        out = subprocess.run(
            [str(dest), "--cli", "status"],
            env=env,
            capture_output=True,
            text=True,
            timeout=SUBPROCESS_TIMEOUT,
        )
        assert out.returncode == 0, out.stderr
        return out.stdout


class TestWrapperStaleLock(unittest.TestCase):
    def test_default_true_when_unset(self):
        self.assertEqual(run_wrapper(None), "true")

    def test_explicit_false_preserved(self):
        self.assertEqual(
            run_wrapper({"JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS": "false"}),
            "false",
        )


if __name__ == "__main__":
    unittest.main()
