"""Enforce the implemented production UI/runtime module boundaries."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
ALLOWED = {
    "app": {"interaction-ui", "interaction-domain", "interaction-data", "runtime-api", "runtime-android"},
    "runtime-api": set(),
    "runtime-engine": {"runtime-api"},
    "runtime-android": {"runtime-api", "runtime-engine", "termux-core", "bootstrap-arm64"},
    "interaction-domain": set(),
    "interaction-data": {"interaction-domain", "runtime-api"},
    "interaction-ui": {"interaction-domain"},
}


class ModuleBoundaryTests(unittest.TestCase):
    def test_production_project_dependencies_follow_design(self):
        for module, allowed in ALLOWED.items():
            build = ROOT / module / "build.gradle.kts"
            self.assertTrue(build.is_file(), f"Required module is missing: {module}")
            dependencies = set(re.findall(r'(?:implementation|api)\(project\(":([^"]+)"\)\)', build.read_text()))
            self.assertFalse(dependencies - allowed, f"{module} has forbidden edges: {dependencies - allowed}")

    def test_production_imports_do_not_cross_layers(self):
        for module in ALLOWED:
            for source in (ROOT / module / "src/main").rglob("*.kt"):
                text = source.read_text()
                if module.startswith("runtime-"):
                    self.assertNotRegex(text, r"com\.mobby\.(?:app|interaction)\.", str(source))
                if module in {"runtime-api", "runtime-engine", "interaction-domain"}:
                    self.assertNotRegex(text, r"(?m)^import (?:android\.|androidx\.|com\.libtermux\.)", str(source))
                if module in {"interaction-domain", "interaction-ui"}:
                    self.assertNotRegex(text, r"com\.mobby\.(?:app|runtime|interaction\.data)\.", str(source))
