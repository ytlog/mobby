"""Reject release mappings that break sherpa-onnx's name-based JNI ABI."""

import argparse
from pathlib import Path


PREFIX = "com.k2fsa.sherpa.onnx."


def violations(mapping: str, seeds: str) -> list[str]:
    problems = []
    in_sherpa = False
    found_config = False
    for line in mapping.splitlines():
        if line and not line[0].isspace() and " -> " in line and line.endswith(":"):
            original, renamed = line[:-1].split(" -> ", 1)
            in_sherpa = original.startswith(PREFIX)
            if in_sherpa:
                found_config |= original == PREFIX + "OnlineRecognizerConfig"
                if original != renamed:
                    problems.append(f"JNI class renamed: {original} -> {renamed}")
            continue
        if not in_sherpa or " -> " not in line or "(" in line:
            continue
        original, renamed = line.strip().split(" -> ", 1)
        field = original.rsplit(" ", 1)[-1]
        if field != renamed:
            problems.append(f"JNI field renamed: {field} -> {renamed}")
    if not found_config:
        problems.append("OnlineRecognizerConfig missing from release mapping")
    if "com.k2fsa.sherpa.onnx.OnlineRecognizerConfig: java.lang.String decodingMethod" not in seeds:
        problems.append("OnlineRecognizerConfig.decodingMethod missing from release seeds")
    return problems


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mapping", type=Path)
    parser.add_argument("seeds", type=Path)
    args = parser.parse_args()
    failures = violations(args.mapping.read_text(), args.seeds.read_text())
    if failures:
        parser.exit(1, "\n".join(failures) + "\n")
