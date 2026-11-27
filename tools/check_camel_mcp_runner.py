#!/usr/bin/env python3
"""Check remote availability of the Camel MCP runner before deploying a Kit snapshot."""

import os
import subprocess
import tempfile
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from sync_distribution_mirrors import load_properties


def check_runner(root):
    properties = load_properties(root / "distribution.properties")
    version = properties["camel.mcp.version"]
    if version.endswith("-SNAPSHOT"):
        release = version.removesuffix("-SNAPSHOT")
        url = (f"https://repo.maven.apache.org/maven2/org/apache/camel/camel-jbang-mcp/{release}/"
               f"camel-jbang-mcp-{release}-runner.jar")
        try:
            urlopen(Request(url, method="HEAD"), timeout=30).close()
        except HTTPError as error:
            if error.code != 404:
                raise
            error.close()
        else:
            raise ValueError(f"Camel MCP runner {release} is available on Maven Central. "
                             "Verify the release and update the pin and catalog results per docs/releasing.md.")

    plugin = properties["maven.dependency.plugin.version"]
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    with tempfile.TemporaryDirectory() as repository:
        subprocess.run([str(wrapper), "-B", "-ntp", "-N", f"-Dmaven.repo.local={repository}",
                        f"org.apache.maven.plugins:maven-dependency-plugin:{plugin}:get",
                        f"-Dartifact=org.apache.camel:camel-jbang-mcp:{version}:jar:runner",
                        "-Dtransitive=false"], cwd=root, check=True)


if __name__ == "__main__":
    try:
        check_runner(Path(__file__).resolve().parents[1])
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        raise SystemExit(str(failure))
