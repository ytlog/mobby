"""Enforce the implemented production UI/runtime module boundaries."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
ALLOWED = {
    "app": {"interaction-ui", "interaction-domain", "interaction-data", "runtime-api", "runtime-android", "device-plugins", "local-model"},
    "runtime-api": set(),
    "runtime-engine": {"runtime-api"},
    "runtime-android": {"runtime-api", "runtime-engine", "termux-core", "bootstrap-arm64", "device-plugins", "plugin:appfunction"},
    "plugin:appfunction": {"device-interaction", "runtime-api"},
    "device-plugins": {"runtime-api"},
    "interaction-domain": set(),
    "interaction-data": {"interaction-domain", "runtime-api"},
    "interaction-ui": {"interaction-domain", "speech", "device-plugins"},
    "local-model": {"local-model-backend-llama"},
    "local-model-backend-llama": set(),
}
ALLOWED["device-interaction"] = set()
ALLOWED["device-interaction-ui"] = {"device-interaction"}
for module in ("runtime-api", "runtime-engine", "runtime-android", "device-plugins", "interaction-domain", "interaction-data", "interaction-ui"):
    ALLOWED[module].add("device-interaction")
ALLOWED["interaction-ui"].add("device-interaction-ui")
ALLOWED["speech"] = set()
ALLOWED["localization"] = set()
for module in ALLOWED:
    if module != "localization":
        ALLOWED[module].add("localization")

# settings.gradle.kts gives this module a logical name distinct from its source directory.
MODULE_DIRS = {"plugin:appfunction": "app-functions"}


class ModuleBoundaryTests(unittest.TestCase):
    def test_production_project_dependencies_follow_design(self):
        for module, allowed in ALLOWED.items():
            build = ROOT / MODULE_DIRS.get(module, module) / "build.gradle.kts"
            self.assertTrue(build.is_file(), f"Required module is missing: {module}")
            dependencies = set(re.findall(r'(?:implementation|api)\(project\(":([^"]+)"\)\)', build.read_text()))
            self.assertFalse(dependencies - allowed, f"{module} has forbidden edges: {dependencies - allowed}")

    def test_production_imports_do_not_cross_layers(self):
        for module in ALLOWED:
            for source in (ROOT / MODULE_DIRS.get(module, module) / "src/main").rglob("*.kt"):
                text = source.read_text()
                if module.startswith("runtime-"):
                    self.assertNotRegex(text, r"com\.mobby\.(?:app|interaction)\.", str(source))
                if module in {"runtime-api", "runtime-engine", "interaction-domain", "device-interaction"}:
                    self.assertNotRegex(text, r"(?m)^import (?:android\.|androidx\.|com\.libtermux\.)", str(source))
                if module in {"interaction-domain", "interaction-ui"}:
                    self.assertNotRegex(text, r"com\.mobby\.(?:app|runtime|interaction\.data)\.", str(source))

    def test_device_cards_depend_only_on_the_contract_and_localization(self):
        for module in ("device-interaction", "device-interaction-ui"):
            for source in (ROOT / module / "src/main").rglob("*.kt"):
                self.assertNotRegex(source.read_text(), r"(?m)^import com\.github\.ytlog\.mobby\.android\.(?:runtime|interaction|device)\.", str(source))
