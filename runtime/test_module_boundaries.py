"""Enforce Gradle and package boundaries after module consolidation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASE = "com.github.ytlog.mobby.android."
ALLOWED = {
    "app": {"interaction-domain", "interaction-data", "runtime-api", "runtime-android", "device-plugins", "local-model", "speech", "localization"},
    "runtime-api": set(),
    "runtime-engine": {"runtime-api", "localization"},
    "runtime-android": {"runtime-api", "runtime-engine", "termux-core", "bootstrap-arm64", "device-plugins", "localization"},
    "device-plugins": {"runtime-api", "localization"},
    "interaction-domain": {"runtime-api", "localization"},
    "interaction-data": {"interaction-domain", "runtime-api", "localization"},
    "speech": {"localization"},
    "localization": set(),
    "local-model": {"local-model-backend-llama", "localization"},
    "local-model-backend-llama": set(),
}
REMOVED = {"interaction-ui", "device-interaction", "device-interaction-ui", "plugin:appfunction"}


def project_references(source):
    # Scan qualified usages too, so spelling an implementation inline cannot bypass imports.
    source = re.sub(r"(?m)^package\s+[^\n]+", "", source)
    return set(re.findall(r"\b" + re.escape(BASE) + r"(?:[A-Za-z_]|\*)[\w.*]*", source))


def forbidden_references(source, allowed_prefixes):
    return {ref for ref in project_references(source)
            if not any(ref == BASE + prefix.rstrip(".") or ref.startswith(BASE + prefix) for prefix in allowed_prefixes)}


class ModuleBoundaryTests(unittest.TestCase):
    def test_settings_has_exactly_the_supported_modules(self):
        settings = (ROOT / "settings.gradle.kts").read_text()
        includes = re.findall(r"include\(([^)]*)\)", settings)
        modules = set(re.findall(r'":([^"]+)"', " ".join(includes)))
        self.assertEqual(set(ALLOWED) | {"termux-core", "bootstrap-arm64"}, modules)
        for directory in ("interaction-ui", "device-interaction", "device-interaction-ui", "app-functions"):
            self.assertFalse((ROOT / directory / "src").exists(), f"Old source tree remains: {directory}")

    def test_production_project_dependencies_follow_design(self):
        for module, allowed in ALLOWED.items():
            build = ROOT / module / "build.gradle.kts"
            self.assertTrue(build.is_file(), f"Required module is missing: {module}")
            dependencies = set(re.findall(r'(?:implementation|api)\(project\(":([^"]+)"\)\)', build.read_text()))
            self.assertFalse(dependencies - allowed, f"{module} has forbidden edges: {dependencies - allowed}")
            self.assertFalse(set(re.findall(r'project\(":([^"]+)"\)', build.read_text())) & REMOVED)

    def test_production_imports_do_not_cross_layers(self):
        for module in ALLOWED:
            for source in (ROOT / module / "src/main").rglob("*.kt"):
                text = source.read_text()
                if module.startswith("runtime-"):
                    self.assertFalse(any(ref.startswith(BASE + "interaction.") for ref in project_references(text)), str(source))
                if module in {"runtime-api", "runtime-engine", "interaction-domain", "localization"}:
                    self.assertNotRegex(text, r"(?m)^import (?:android\.|androidx\.|com\.libtermux\.)", str(source))
                if module == "runtime-api":
                    self.assertFalse(forbidden_references(text, ("runtime.api.",)), str(source))
                if module == "interaction-domain":
                    self.assertFalse(forbidden_references(text, ("interaction.domain.", "localization.", "runtime.api.device.")), str(source))
                package = re.search(r"(?m)^package\s+(\S+)", text)
                if module == "app" and package and (package[1].startswith(BASE + "interaction.ui") or package[1].startswith(BASE + "deviceinteraction")):
                    self.assertFalse(forbidden_references(text, (
                        "interaction.ui.", "interaction.domain.", "deviceinteraction.",
                        "runtime.api.device.", "speech.", "localization.", "R.",
                    )), str(source))

    def test_checker_rejects_current_namespace_and_inline_implementation_access(self):
        for text in (
            f"import {BASE}device.DeviceStorage",
            f"import {BASE}*",
            f"val store = {BASE}interaction.data.InteractionDatabase.open(context)",
            f"import {BASE}runtime.api.*",
            f"import {BASE}runtime.android.RuntimeHost as Host",
        ):
            self.assertTrue(forbidden_references(text, ("interaction.domain.", "runtime.api.device.")), text)
        self.assertFalse(forbidden_references(f"import {BASE}runtime.api.device.*", ("runtime.api.device.",)))

    def test_host_and_native_dependencies_stay_separate_from_local_model_service(self):
        for source in (ROOT / "local-model/src/main").rglob("*.kt"):
            self.assertFalse(forbidden_references(source.read_text(), ("localmodel.", "localization.")), str(source))
        manifest = (ROOT / "local-model/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:process=":local_model"', manifest)
