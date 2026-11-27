import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from check_camel_mcp_runner import check_runner


class CamelMcpRunnerTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def pin(self, version):
        (self.root / "distribution.properties").write_text(
            f"camel.mcp.version={version}\nmaven.dependency.plugin.version=3.8.1\n")

    @patch("check_camel_mcp_runner.subprocess.run")
    @patch("check_camel_mcp_runner.urlopen")
    def test_published_runner_stops_snapshot_deployment(self, remote, maven):
        self.pin("1.2.3-SNAPSHOT")
        with self.assertRaisesRegex(ValueError, r"runner 1\.2\.3 is available"):
            check_runner(self.root)
        request = remote.call_args.args[0]
        self.assertEqual("HEAD", request.get_method())
        self.assertTrue(request.full_url.endswith("/1.2.3/camel-jbang-mcp-1.2.3-runner.jar"))
        maven.assert_not_called()

    @patch("check_camel_mcp_runner.urlopen")
    def test_registry_error_is_reported(self, remote):
        self.pin("1.2.3-SNAPSHOT")
        remote.side_effect = HTTPError("central", 503, "unavailable", {}, None)
        with self.assertRaises(HTTPError):
            check_runner(self.root)

    @patch("check_camel_mcp_runner.urlopen")
    def test_resolves_snapshot_and_release_without_cached_artifacts(self, remote):
        remote.side_effect = HTTPError("central", 404, "missing", {}, None)
        for version in ("1.2.3-SNAPSHOT", "1.2.3"):
            with self.subTest(version=version):
                self.pin(version)

                def verify(command, *, cwd, check):
                    self.assertTrue(check)
                    self.assertEqual(self.root, cwd)
                    self.assertIn(f"-Dartifact=org.apache.camel:camel-jbang-mcp:{version}:jar:runner", command)
                    repo = next(arg.split("=", 1)[1] for arg in command if arg.startswith("-Dmaven.repo.local="))
                    self.assertEqual([], list(Path(repo).iterdir()))

                with patch("check_camel_mcp_runner.subprocess.run", side_effect=verify) as maven:
                    check_runner(self.root)
                    maven.assert_called_once()
        remote.assert_called_once()
