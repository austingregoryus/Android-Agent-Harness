"""Test the public-export boundary with disposable Git repositories."""
from pathlib import Path
import subprocess
import tempfile
import unittest

import publication


class PublicationTest(unittest.TestCase):
    def test_export_omits_history_secrets_and_existing_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "source"
            root.mkdir()
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / "README.md").write_text("Public docs", encoding="utf-8")
            (root / "secrets").mkdir()
            (root / "secrets" / "token").write_text("private-value", encoding="utf-8")
            (root / "docs").mkdir()
            (root / "docs" / "signing.key").write_text("private-value", encoding="utf-8")
            paths = publication.selected(root)
            self.assertEqual([root / "README.md"], paths)
            dest = Path(temp) / "export"
            publication.export(dest, paths, root)
            self.assertFalse((dest / ".git").exists())
            self.assertFalse((dest / "secrets").exists())
            self.assertTrue((dest / "PUBLICATION_MANIFEST.json").exists())
            self.assertEqual([dest / "README.md"], publication.selected(dest))
            with self.assertRaises(ValueError):
                publication.export(dest, paths, root)

    def test_findings_are_redacted_and_unknown_binaries_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "README.md"
            value = "sk-" + "a" * 40
            source.write_text(value, encoding="utf-8")
            issues = publication.check([source], root)
            self.assertTrue(issues)
            self.assertNotIn(value, "\n".join(issues))
            opaque = root / "recording.pcm"
            opaque.write_bytes(b"\0\1")
            self.assertTrue(publication.check([opaque], root))


if __name__ == "__main__":
    unittest.main()
