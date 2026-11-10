"""Check report evidence; exit 0 for no findings, 1 for findings, 2 for incomplete reports."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def inspect_reports(directory):
    """Read bounded report fields without printing source snippets or diagnostics."""
    for filename in ("main.html", "main.xml"):
        if not (directory / filename).read_bytes().strip():
            raise ValueError("empty report")
    collection = ET.parse(directory / "main.xml").getroot()
    errors = collection.find("Errors")
    inputs = [jar for jar in collection.findall("./Project/Jar") if jar.text and jar.text.strip()]
    if collection.tag != "BugCollection" or errors is None or not inputs:
        raise ValueError("missing report evidence")
    error_count = int(errors.attrib["errors"])
    missing_count = int(errors.attrib["missingClasses"])
    if min(error_count, missing_count) < 0:
        raise ValueError("negative diagnostic count")
    return (len(inputs), len(collection.findall("BugInstance")),
            max(error_count, len(errors.findall("Error"))),
            max(missing_count, len(collection.findall(".//MissingClass"))))


def main():
    """Validate the API pair and every discovered module pair, preserving any failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    root = parser.parse_args().root.resolve()
    directories = {root / "api/build/reports/spotbugs"}
    for filename in ("main.html", "main.xml"):
        directories.update(path.parent for path in root.glob(f"**/build/reports/spotbugs/{filename}"))

    status = 0
    for directory in sorted(directories):
        module = directory.relative_to(root).as_posix().removesuffix("/build/reports/spotbugs")
        try:
            inputs, findings, errors, missing = inspect_reports(directory)
        except (OSError, ET.ParseError, ValueError, KeyError):
            print(f"{module}: INCOMPLETE_ANALYSIS — missing, empty or invalid report evidence")
            status = 2
            continue
        print(f"{module}: inputs={inputs}, findings={findings}, errors={errors}, missing_classes={missing}")
        if errors or missing:
            print(f"{module}: INCOMPLETE_ANALYSIS — inspect analyzer logs and reports")
            status = 2
        elif findings:
            status = max(status, 1)

    if status:
        print("HUMAN_REVIEW_REQUIRED — inspect analyzer outcome, logs and reports")
    else:
        print("configured checks reported no findings; this does not establish absence of defects")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
