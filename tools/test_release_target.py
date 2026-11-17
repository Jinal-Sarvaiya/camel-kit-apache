import tempfile
import unittest
import os
import shutil
import subprocess
from pathlib import Path

from release_target import validate


class ReleaseTargetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "distribution.properties").write_text("camel.cli.version=4.24.0\n")
        self.pom("pom.xml", "camel-kit-parent", "1.0.0", modules=["kit", "knowledge"])
        self.pom("kit/pom.xml", "camel-kit", "0.4.2", "camel-kit-parent", "1.0.0", ["core"])
        self.pom("kit/core/pom.xml", "camel-kit-core", None, "camel-kit", "0.4.2")
        self.pom("knowledge/pom.xml", "camel-kit-knowledge", "0.0.3-SNAPSHOT", "camel-kit-parent", "1.0.0", ["mcp"])
        self.pom("knowledge/mcp/pom.xml", "camel-kit-knowledge-mcp", None, "camel-kit-knowledge", "0.0.3-SNAPSHOT")

    def pom(self, path, artifact, version, parent=None, parent_version=None, modules=()):
        content = '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        if parent:
            content += f"<parent><groupId>org.apache.camel</groupId><artifactId>{parent}</artifactId><version>{parent_version}</version></parent>"
        content += f"<artifactId>{artifact}</artifactId>"
        if version:
            content += f"<version>{version}</version>"
            content += f"<scm><tag>{artifact}-{version}</tag></scm>"
        if artifact == "camel-kit":
            content += "<properties><camel.cli.version>4.24.0</camel.cli.version></properties>"
        content += "<modules>" + "".join(f"<module>{m}</module>" for m in modules) + "</modules></project>"
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content)

    def test_kit_release_ignores_knowledge_snapshot(self):
        self.assertEqual(("0.4.2", ["-f", "kit/pom.xml"]), validate(self.root, "kit", "camel-kit-0.4.2"))

    def test_parent_cannot_recurse_into_products(self):
        self.assertEqual(("1.0.0", ["-N"]), validate(self.root, "parent", "camel-kit-parent-1.0.0"))

    def test_kit_release_requires_a_released_camel_host(self):
        for version in ["4.24.0-SNAPSHOT", "4.24.0-20261117.143648-37", "4.24.0-RC1", ""]:
            with self.subTest(version=version):
                (self.root / "distribution.properties").write_text(f"camel.cli.version={version}\n")
                with self.assertRaisesRegex(ValueError, "released Camel CLI host"):
                    validate(self.root, "kit", "camel-kit-0.4.2")

    def test_host_snapshot_is_allowed_for_development_and_other_products(self):
        (self.root / "distribution.properties").write_text("camel.cli.version=4.24.0-SNAPSHOT\n")
        self.pom("kit/pom.xml", "camel-kit", "0.4.2-SNAPSHOT", "camel-kit-parent", "1.0.0", ["core"])
        self.pom("kit/core/pom.xml", "camel-kit-core", None, "camel-kit", "0.4.2-SNAPSHOT")
        self.assertEqual("0.4.2-SNAPSHOT", validate(self.root, "kit", snapshot=True)[0])
        self.assertEqual("1.0.0", validate(self.root, "parent")[0])
        self.assertEqual("0.0.3-SNAPSHOT", validate(self.root, "knowledge", snapshot=True)[0])

    def test_kit_release_rejects_an_older_picocli_host(self):
        (self.root / "distribution.properties").write_text("camel.cli.version=4.22.1\n")
        pom = self.root / "kit/pom.xml"
        pom.write_text(pom.read_text().replace("4.24.0", "4.22.1"))
        with self.assertRaisesRegex(ValueError, "released Camel CLI host"):
            validate(self.root, "kit")

    def test_kit_release_rejects_a_stale_host_mirror(self):
        pom = self.root / "kit/pom.xml"
        pom.write_text(pom.read_text().replace("4.24.0", "4.24.0-SNAPSHOT"))
        with self.assertRaisesRegex(ValueError, "Synchronize the Camel CLI host"):
            validate(self.root, "kit")

    def test_knowledge_snapshot_has_its_own_scope(self):
        self.assertEqual(("0.0.3-SNAPSHOT", ["-f", "knowledge/pom.xml"]), validate(self.root, "knowledge", snapshot=True))
        with self.assertRaises(ValueError):
            validate(self.root, "knowledge")

    def test_product_requires_released_parent(self):
        self.pom("kit/pom.xml", "camel-kit", "0.4.2", "camel-kit-parent", "1.0.0-SNAPSHOT", ["core"])
        with self.assertRaisesRegex(ValueError, "released shared parent"):
            validate(self.root, "kit")

    def test_mismatched_module_is_rejected(self):
        self.pom("kit/core/pom.xml", "camel-kit-core", None, "camel-kit", "0.4.1")
        with self.assertRaisesRegex(ValueError, "versions must match"):
            validate(self.root, "kit")

    def test_wrong_product_tag_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Tag must be"):
            validate(self.root, "kit", "camel-kit-knowledge-0.4.2")

    def test_scm_tag_must_identify_the_selected_release(self):
        path = self.root / "kit/pom.xml"
        path.write_text(path.read_text().replace("<tag>camel-kit-0.4.2</tag>", "<tag>HEAD</tag>"))
        with self.assertRaisesRegex(ValueError, "SCM tag must match"):
            validate(self.root, "kit", "camel-kit-0.4.2")

    def test_release_script_invokes_only_the_selected_reactor(self):
        repository = Path(__file__).resolve().parent.parent
        (self.root / "tools").mkdir()
        shutil.copy(repository / "tools/release_target.py", self.root / "tools/release_target.py")
        shutil.copy(repository / "tools/sync_distribution_mirrors.py", self.root / "tools/sync_distribution_mirrors.py")
        wrapper = self.root / "mvnw"
        wrapper.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@"\n')
        wrapper.chmod(0o755)
        self.pom("knowledge/pom.xml", "camel-kit-knowledge", "0.0.3", "camel-kit-parent", "1.0.0", ["mcp"])
        self.pom("knowledge/mcp/pom.xml", "camel-kit-knowledge-mcp", None, "camel-kit-knowledge", "0.0.3")
        for target, tag, scope in [
            ("parent", "camel-kit-parent-1.0.0", ["-N"]),
            ("kit", "camel-kit-0.4.2", ["-f", "kit/pom.xml"]),
            ("knowledge", "camel-kit-knowledge-0.0.3", ["-f", "knowledge/pom.xml"]),
        ]:
            with self.subTest(target=target):
                result = subprocess.run(["bash", str(repository / ".github/workflows/release.sh")],
                                        cwd=self.root, capture_output=True, text=True, check=True,
                                        env={**os.environ, "RELEASE_TARGET": target, "RELEASE_TAG": tag,
                                             "GPG_ID": "test-key"})
                arguments = result.stdout.splitlines()
                self.assertEqual(scope, arguments[1:1 + len(scope)])
                self.assertNotIn("-am", arguments)
                self.assertEqual(["clean", "deploy"], arguments[-2:])


if __name__ == "__main__":
    unittest.main()
