#!/usr/bin/env python3
"""Validate an independent release and print its bounded Maven argument list."""

import argparse
import re
import urllib.request
from pathlib import Path
import xml.etree.ElementTree as ET

from sync_distribution_mirrors import load_properties

NS = "{http://maven.apache.org/POM/4.0.0}"
TARGETS = {
    "parent": ("pom.xml", "camel-kit-parent", ["-N"]),
    "kit": ("kit/pom.xml", "camel-kit", ["-f", "kit/pom.xml"]),
    "knowledge": ("knowledge/pom.xml", "camel-kit-knowledge", ["-f", "knowledge/pom.xml"]),
}


def validate(root, target, tag=None, snapshot=False):
    path, artifact, arguments = TARGETS[target]
    pom = ET.parse(root / path).getroot()
    version = pom.findtext(NS + "version", "")
    pattern = r"[0-9]+\.[0-9]+\.[0-9]+" + ("-SNAPSHOT" if snapshot else "")
    if not re.fullmatch(pattern, version):
        raise ValueError(f"{path}: expected a {'snapshot' if snapshot else 'release'} version, got {version}")
    if pom.findtext(NS + "artifactId") != artifact:
        raise ValueError(f"{path}: unexpected artifactId")
    if tag is not None and tag != f"{artifact}-{version}":
        raise ValueError(f"Tag must be {artifact}-{version}")
    if tag is not None and pom.findtext(f"{NS}scm/{NS}tag") != tag:
        raise ValueError(f"{path}: SCM tag must match {tag}")
    if target != "parent":
        parent = pom.find(NS + "parent")
        if (parent is None or parent.findtext(NS + "artifactId") != "camel-kit-parent"
                or parent.findtext(NS + "groupId") != "org.apache.camel"):
            raise ValueError(f"{path}: unexpected shared parent")
        parent_version = parent.findtext(NS + "version", "")
        if not snapshot and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", parent_version):
            raise ValueError(f"{path}: a released shared parent is required")
        for module in pom.findall(f"{NS}modules/{NS}module"):
            child_path = (root / path).parent / module.text / "pom.xml"
            child = ET.parse(child_path).getroot()
            if (child.findtext(f"{NS}parent/{NS}artifactId") != artifact
                    or child.findtext(f"{NS}parent/{NS}groupId") != "org.apache.camel"
                    or child.findtext(f"{NS}parent/{NS}version") != version
                    or child.findtext(NS + "version", version) != version):
                raise ValueError(f"{child_path}: module and product versions must match {version}")
    if target == "kit" and not snapshot:
        distribution = load_properties(root / "distribution.properties")
        host = distribution.get("camel.cli.version", "")
        if (not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", host)
                or tuple(map(int, host.split("."))) < (4, 24, 0)):
            raise ValueError(f"A released Camel CLI host 4.24.0 or newer is required, got {host!r}")
        if pom.findtext(f"{NS}properties/{NS}camel.cli.version") != host:
            raise ValueError("Synchronize the Camel CLI host version in kit/pom.xml before releasing")
        runner = distribution.get("camel.mcp.version", "")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", runner):
            raise ValueError(f"A released Camel MCP runner is required for camel.mcp.version, got {runner!r}")
    return version, list(arguments)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=TARGETS)
    parser.add_argument("--tag")
    parser.add_argument("--snapshot", action="store_true")
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--check-published-parent", action="store_true")
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parent.parent
        version, arguments = validate(root, args.target, args.tag, args.snapshot)
        if args.check_published_parent and args.target != "parent":
            if args.snapshot:
                raise ValueError("The published-parent check is for releases only")
            pom = ET.parse(root / TARGETS[args.target][0]).getroot()
            parent_version = pom.findtext(f"{NS}parent/{NS}version")
            url = ("https://repo.maven.apache.org/maven2/org/apache/camel/camel-kit-parent/"
                   f"{parent_version}/camel-kit-parent-{parent_version}.pom")
            # Check the public repository directly so a local/reactor POM cannot mask an unpublished parent.
            with urllib.request.urlopen(url, timeout=15) as response:
                published = ET.fromstring(response.read(1024 * 1024))
            if (published.findtext(NS + "groupId") != "org.apache.camel"
                    or published.findtext(NS + "artifactId") != "camel-kit-parent"
                    or published.findtext(NS + "version") != parent_version):
                raise ValueError("Published shared parent does not match the selected coordinates")
    except (ValueError, OSError, ET.ParseError) as failure:
        parser.error(str(failure))
    print(version if args.version else "\n".join(arguments))


if __name__ == "__main__":
    main()
