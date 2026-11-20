#!/usr/bin/env python3
"""The dependency matrix and the vulnerability scan of ws-client's published modules.

Input: target/dependency-report/*.tsv, written by `sbt +dependencyExport` - the resolved
runtime dependencies of every published module for every Scala version. Libraries that a jar
BUNDLES under another name (shaded / relocated / vendored code) are listed and scanned too:
Play WS's AsyncHttpClient and Netty and jars with embedded Maven metadata are read from the
jars, the others come from the BUNDLES rules below - and a jar that carries relocated code no
rule knows FAILS the check.

  security/dependencies.py            check: the generated docs are current, and no artifact
                                      has a known vulnerability (OSV.dev) that is not accepted
                                      in security/accepted.json
  security/dependencies.py --write    rewrite the README section and docs/dependencies.md
  security/dependencies.py --offline  no vulnerability scan (the docs only)

  --reports DIR     the dependency reports to read (default target/dependency-report)
  --no-tables       do not check the generated docs (e.g. for a release's reports)
  --badges DIR      write shields.io endpoint files and status.json for the README badges
  --label TEXT      names the scanned tree in the badge files (default "main")

Exit codes: 0 fine; 1 an advisory that is not accepted; 2 the repository needs an update (the
generated docs, a stale accepted entry, a bundling jar without a rule); 3 the scan could not
run (OSV.dev unreachable, no reports).

Python 3 standard library only.
"""

import argparse
import datetime
import glob
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ACCEPTED = os.path.join(ROOT, "security", "accepted.json")
README = os.path.join(ROOT, "README.md")
PAGE = os.path.join(ROOT, "docs", "dependencies.md")
START, END = "<!-- dependency-matrix:start -->", "<!-- dependency-matrix:end -->"
SCALA_VERSIONS = ["2.12", "2.13", "3"]
SCALA_SUFFIXES = ["_2.12", "_2.13", "_3"]
OSV = "https://api.osv.dev/v1"
REPOSITORY = "cequence-io/ws-client"
STATUS_BRANCH = "security-status"

EXIT_ADVISORY, EXIT_REPOSITORY, EXIT_INFRASTRUCTURE = 1, 2, 3

# What a jar bundles under another name where the jar itself does not say: carrier (Scala
# suffix stripped) -> carrier version -> [(coordinates, version)]. A version of None is a
# vendored source copy without upstream coordinates: listed, not scannable. Every version of a
# carrier needs its entry - an unknown one fails the check - taken from the carrier's build:
#   akka-protobuf-v3:  akka/akka project/Dependencies.scala, `protobufJavaVersion`
#   pekko-protobuf-v3: apache/pekko project/Dependencies.scala, `protobufJavaVersion`
#   shaded-oauth:      playframework/play-ws project/Dependencies.scala, `oauth` (signpost-core
#                      1.2.1.2 depends on commons-codec 1.3, shaded with it)
#   akka-http-core / pekko-http-core: a source copy of Twitter's HPACK under `http.shaded`
#   jackson-core:      FasterXML/jackson-core pom.xml, the shaded `fastdoubleparser`
#   izumi-reflect-thirdparty-boopickle-shaded: zio/izumi-reflect's own copy of boopickle
BUNDLES = {
    "com.typesafe.akka:akka-protobuf-v3": {
        "2.6.20": [("com.google.protobuf:protobuf-java", "3.16.1")],
        "2.6.21": [("com.google.protobuf:protobuf-java", "3.16.1")],
    },
    "org.apache.pekko:pekko-protobuf-v3": {
        "1.6.0": [("com.google.protobuf:protobuf-java", "4.33.5")],
    },
    "com.typesafe.play:shaded-oauth": {
        "2.1.11": [
            ("oauth.signpost:signpost-core", "1.2.1.2"),
            ("commons-codec:commons-codec", "1.3"),
        ],
        "2.2.0-M2": [("oauth.signpost:signpost-core", "2.1.1")],
        "2.2.18": [("oauth.signpost:signpost-core", "2.1.1")],
    },
    "org.playframework:shaded-oauth": {
        "3.0.14": [("oauth.signpost:signpost-core", "2.1.1")],
    },
    "com.typesafe.akka:akka-http-core": {
        "10.5.3": [("com.twitter:hpack", None)],
    },
    "org.apache.pekko:pekko-http-core": {
        "1.3.0": [("com.twitter:hpack", None)],
    },
    "dev.zio:izumi-reflect-thirdparty-boopickle-shaded": {
        "3.0.9": [("io.suzaku:boopickle", None)],
    },
    "com.fasterxml.jackson.core:jackson-core": {
        "2.22.3": [("ch.randelshofer:fastdoubleparser", "2.0.1")],
    },
}

# a class under one of these package segments is relocated or vendored code
RELOCATED = re.compile(r"(^|/)(shaded|shadow|relocated|repackaged|thirdparty|vendor|protobufv3)/")

# the packages of a shaded Netty -> the Netty artifacts they come from
NETTY_PACKAGES = {
    "buffer": "netty-buffer",
    "channel": "netty-transport",
    "channel/epoll": "netty-transport-native-epoll",
    "channel/kqueue": "netty-transport-native-kqueue",
    "channel/unix": "netty-transport-native-unix-common",
    "handler/codec": "netty-codec",
    "handler/codec/dns": "netty-codec-dns",
    "handler/codec/http": "netty-codec-http",
    "handler/codec/http2": "netty-codec-http2",
    "handler/codec/socksx": "netty-codec-socks",
    "handler/proxy": "netty-handler-proxy",
    "handler/ssl": "netty-handler",
    "resolver": "netty-resolver",
    "resolver/dns": "netty-resolver-dns",
    "util": "netty-common",
}
PLAY_AHC = "play/shaded/ahc/org/asynchttpclient/config/ahc-version.properties"


class Failed(Exception):
    """The check cannot go on: `code` is the exit code."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def strip_scala(name):
    """`group:artifact_2.13` -> `group:artifact`."""
    return re.sub(r"_(2\.12|2\.13|3)$", "", name)


def bundled(name, version, jar):
    """What a jar carries under another name: ([(group:artifact, version)], problem or None)."""
    if not jar or not os.path.isfile(jar):
        return [], None
    found = set()
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        own = os.path.basename(jar)
        for entry in names:
            # embedded Maven metadata (fat jars)
            match = re.fullmatch(r"META-INF/maven/([^/]+)/([^/]+)/pom\.properties", entry)
            if match:
                properties = dict(
                    line.split("=", 1)
                    for line in archive.read(entry).decode("utf-8", "replace").splitlines()
                    if "=" in line and not line.startswith("#")
                )
                embedded = properties.get("version", "").strip()
                if embedded and not own.startswith(f"{match.group(2)}-{embedded}"):
                    found.add((f"{match.group(1)}:{match.group(2)}", embedded))
        # Play WS's shaded AsyncHttpClient: relocated classes, the versions in two property files
        if PLAY_AHC in names:
            ahc = re.search(r"ahc\.version=(\S+)", archive.read(PLAY_AHC).decode("utf-8", "replace"))
            netty = re.search(
                r"\.version=(\S+)",
                archive.read("META-INF/io.netty.versions.properties").decode("utf-8", "replace"),
            )
            if not ahc or not netty:
                return [], f"{name} {version}: the bundled AsyncHttpClient / Netty versions are not readable"
            found.add(("org.asynchttpclient:async-http-client", ahc.group(1)))
            prefix = "play/shaded/ahc/io/netty/"
            for package, artifact in NETTY_PACKAGES.items():
                directory = prefix + package + "/"
                if any(
                    n.startswith(directory) and n.endswith(".class") and "/" not in n[len(directory):]
                    for n in names
                ):
                    found.add((f"io.netty:{artifact}", netty.group(1)))
        relocated = PLAY_AHC not in names and any(
            n.endswith(".class") and RELOCATED.search(n) for n in names
        )
    rules = BUNDLES.get(strip_scala(name))
    if rules is not None:
        if version not in rules:
            return [], (
                f"{name} {version}: no BUNDLES rule for this version in security/dependencies.py "
                "- look up what it bundles in its build and add it"
            )
        found.update(rules[version])
    elif relocated and not found:
        return [], (
            f"{name} {version}: the jar carries relocated code (shaded / vendored packages) that "
            "no BUNDLES rule in security/dependencies.py maps to coordinates - add one"
        )
    return sorted(found, key=lambda item: (item[0], item[1] or "")), None


def load(reports):
    """({(module, scala): [entry]}, problems) with entry = dict(kind, name, version, via)."""
    files = sorted(glob.glob(os.path.join(reports, "*.tsv")))
    if not files:
        raise Failed(EXIT_INFRASTRUCTURE, f"No dependency reports in {reports} - run `sbt +dependencyExport` first")
    cache, problems, result = {}, [], {}
    for path in files:
        module, scala = os.path.basename(path)[: -len(".tsv")].rsplit("_", 1)
        entries = []
        with open(path, encoding="utf-8") as report:
            for line in report.read().splitlines():
                kind, name, version, jar = (line.split("\t") + [""])[:4]
                entries.append(dict(kind=kind, name=name, version=version, via=None))
                if jar not in cache:
                    cache[jar] = bundled(name, version, jar)
                    if cache[jar][1]:
                        problems.append(cache[jar][1])
                for bundled_name, bundled_version in cache[jar][0]:
                    entries.append(
                        dict(kind="bundled", name=bundled_name, version=bundled_version, via=name)
                    )
        result[(module, scala)] = entries
    return result, sorted(set(problems))


def scannable(report):
    """Every (name, version) that has coordinates and a version."""
    return sorted({
        (e["name"], e["version"]) for entries in report.values() for e in entries if e["version"]
    })


# ------------------------------------------------------------------------------ rendering


def shown(version):
    return version if version else "source copy"


def versions_by_scala(report, name):
    """{scala: sorted versions} of the resolved library `name` (Scala suffix stripped) - a
    jar of its own, not a copy of it bundled inside another jar."""
    result = {}
    for (_, scala), entries in report.items():
        for entry in entries:
            if strip_scala(entry["name"]) == name and entry["kind"] != "bundled":
                result.setdefault(scala, set()).add(shown(entry["version"]))
    return {scala: sorted(found) for scala, found in result.items()}


def cells(by_scala):
    return [" / ".join(by_scala.get(scala, [])) or "-" for scala in SCALA_VERSIONS]


def short(module):
    return module[len("ws-client-"):] if module.startswith("ws-client-") else module


def per_scala(by_scala, scalas):
    """One cell for a version per Scala version: the version alone where it is the same for
    every one of `scalas` (the Scala versions the bundling jar is resolved for)."""
    versions = {scala: " / ".join(sorted(found)) for scala, found in by_scala.items()}
    if len(set(versions.values())) == 1 and set(versions) == set(scalas):
        return next(iter(versions.values()))
    grouped = {}
    for scala in SCALA_VERSIONS:
        if scala in versions:
            grouped.setdefault(versions[scala], []).append(scala)
    return ", ".join(f"{version} (Scala {', '.join(scalas)})" for version, scalas in grouped.items())


def bundle_rows(report, carrier):
    """label -> {scala: versions} of what `carrier` bundles; Netty's artifacts share one row."""
    rows = {}
    for (_, scala), entries in report.items():
        for entry in entries:
            if entry["kind"] == "bundled" and strip_scala(entry["via"]) == carrier:
                label = "Netty" if entry["name"].startswith("io.netty:") else f"`{entry['name']}`"
                rows.setdefault(label, {}).setdefault(scala, set()).add(shown(entry["version"]))
    return rows


def matrix(report):
    """Two tables: the libraries the modules declare, and what jars bundle under their name."""
    declaring = {}
    for (module, _), entries in report.items():
        for entry in entries:
            if entry["kind"] == "direct":
                declaring.setdefault(strip_scala(entry["name"]), set()).add(module)

    scala_libraries = {"org.scala-lang:scala-library", "org.scala-lang:scala3-library"}
    lines = [
        "| Library | Scala 2.12 | Scala 2.13 | Scala 3 | Declared by |",
        "|---|---|---|---|---|",
    ]
    for name in sorted(declaring, key=lambda n: (n in scala_libraries, n)):
        modules = sorted(declaring[name])
        by = "every module" if name in scala_libraries else ", ".join(short(m) for m in modules)
        lines.append(
            "| `{}` | {} | {} | {} | {} |".format(name, *cells(versions_by_scala(report, name)), by)
        )

    carriers = sorted({
        strip_scala(entry["via"])
        for entries in report.values()
        for entry in entries
        if entry["kind"] == "bundled"
    })
    if carriers:
        lines += [
            "",
            "Bundled inside other jars (shaded or vendored - not visible as dependencies):",
            "",
            "| Jar | Bundles | Bundled version |",
            "|---|---|---|",
        ]
        for carrier in carriers:
            scalas = versions_by_scala(report, carrier).keys()
            for label, by_scala in sorted(bundle_rows(report, carrier).items()):
                lines.append(f"| `{carrier}` | {label} | {per_scala(by_scala, scalas)} |")
    return lines


def module_tables(report):
    lines = []
    for module in sorted({module for module, _ in report}):
        scalas = [s for s in SCALA_VERSIONS if (module, s) in report]
        lines += [f"### {module}", "", f"Scala {', '.join(scalas)}.", ""]
        lines += [
            "| Artifact | " + " | ".join(f"Scala {s}" for s in scalas) + " | |",
            "|---|" + "---|" * (len(scalas) + 1),
        ]
        rows = {}
        for scala in scalas:
            for entry in report[(module, scala)]:
                key = (strip_scala(entry["name"]), strip_scala(entry["via"] or ""))
                row = rows.setdefault(key, dict(kinds=set(), versions={}))
                row["kinds"].add(entry["kind"])
                row["versions"].setdefault(scala, set()).add(shown(entry["version"]))
        order = {"direct": 0, "transitive": 1, "bundled": 2}
        ranked = sorted(rows.items(), key=lambda item: (min(order[k] for k in item[1]["kinds"]), item[0]))
        for (name, via), row in ranked:
            kind = min(row["kinds"], key=lambda k: order[k])
            if kind == "direct":
                note = "declared"
            elif kind == "bundled":
                note = f"bundled in `{via.split(':')[1]}`"
            else:
                note = ""
            versions = [" / ".join(sorted(row["versions"].get(s, []))) or "-" for s in scalas]
            lines.append(f"| `{name}` | " + " | ".join(versions) + f" | {note} |")
        lines.append("")
    return lines


def accepted_entries():
    with open(ACCEPTED, encoding="utf-8") as source:
        return json.load(source)["accepted"]


STATUS = {
    "unreachable": "not reachable",
    "mitigated": "closed in ws-client",
    "residual": "**open**",
}


def accepted_table():
    entries = accepted_entries()
    if not entries:
        return ["None."]
    lines = [
        "| Artifact | Versions | Advisories | Status | Why |",
        "|---|---|---|---|---|",
    ]
    order = {"residual": 0, "mitigated": 1, "unreachable": 2}
    for entry in sorted(entries, key=lambda e: (order[e["status"]], e["package"], e["versions"])):
        ids = ", ".join(
            f"[{identifier}](https://osv.dev/vulnerability/{identifier})" for identifier in entry["ids"]
        )
        if entry.get("cves"):
            ids += " (" + ", ".join(entry["cves"]) + ")"
        lines.append(
            "| `{}` | {} | {} | {} | {} |".format(
                entry["package"], ", ".join(entry["versions"]), ids, STATUS[entry["status"]], entry["reason"]
            )
        )
    return lines


def accepted_summary():
    """(distinct advisories, {status: distinct advisories})."""
    by_status = {}
    for entry in accepted_entries():
        by_status.setdefault(entry["status"], set()).update(entry["ids"])
    return sum(len(ids) for ids in by_status.values()), {k: len(v) for k, v in by_status.items()}


def badge(name, label=None):
    """A shields.io badge fed by an endpoint file the scan publishes on the status branch."""
    url = f"https://raw.githubusercontent.com/{REPOSITORY}/{STATUS_BRANCH}/{name}.json"
    encoded = url.replace(":", "%3A").replace("/", "%2F")
    workflow = f"https://github.com/{REPOSITORY}/actions/workflows/vulnerability-scan.yml"
    return f"[![{label or name}](https://img.shields.io/endpoint?url={encoded})]({workflow})"


def readme_section(report):
    total, by_status = accepted_summary()
    residual = [e for e in accepted_entries() if e["status"] == "residual"]
    workflow = f"https://github.com/{REPOSITORY}/actions/workflows/vulnerability-scan.yml"
    lines = [
        START,
        "<!-- generated by security/dependencies.py --write - do not edit -->",
        "",
        f"[![Vulnerability scan]({workflow}/badge.svg)]({workflow}) "
        + " ".join([
            badge("last-scan", "last scan"),
            badge("new-advisories", "new advisories"),
            badge("artifacts", "artifacts scanned"),
            badge("accepted", "accepted advisories"),
            badge("release", "latest release"),
        ])
        + " [![advisories: OSV.dev](https://img.shields.io/badge/advisories-OSV.dev-blue)](https://osv.dev)",
        "",
        f"**The scan.** Every day (and on every dependency change) all {len(scannable(report))} "
        "artifacts the published modules bring - transitive ones and the libraries other jars "
        "bundle under their own name (Play WS's AsyncHttpClient and Netty, Akka's and Pekko's "
        "protobuf, ...) - are checked against [OSV.dev](https://osv.dev) (GitHub advisories, "
        "CVEs), for `main` and for the latest release. The badges above show the last run. A new "
        "advisory fails the scan and opens an issue: the dependency is upgraded, or - where no "
        "fixed version can be used - the advisory is accepted with its reason.",
        "",
        f"**Accepted today:** {total} advisories, all in libraries that Play WS and Akka bundle - "
        f"{by_status.get('unreachable', 0)} not reachable through ws-client, "
        f"{by_status.get('mitigated', 0)} closed in ws-client's own code, "
        f"{by_status.get('residual', 0)} open.",
    ]
    if residual:
        lines += ["", "Open:"]
        for entry in residual:
            lines.append(
                "- `{}` {} ({}): {}".format(
                    entry["package"].split(":")[1],
                    ", ".join(entry["versions"]),
                    ", ".join(entry.get("cves") or entry["ids"]),
                    entry["reason"].replace("NOT closed. ", ""),
                )
            )
    lines += [
        "",
        "**The dependencies** the modules declare on `main` (the next release), with the versions "
        "each Scala version resolves. A module brings the libraries of its own rows and of the "
        "modules it builds on; where a cell lists several versions, a module further up resolves "
        "a newer one. Per module, the full lists and every accepted advisory: "
        "[Dependencies and vulnerabilities](docs/dependencies.md).",
        "",
    ]
    lines += matrix(report)
    lines.append(END)
    return "\n".join(lines)


def page(report):
    lines = [
        "# Dependencies and vulnerabilities 🔐",
        "",
        "[← back to the README](../README.md)",
        "",
        "What every published ws-client module brings onto a classpath - per Scala version, "
        "transitive dependencies and the libraries bundled inside other jars included - and how "
        "known vulnerabilities in them are tracked. The tables are generated from the build on "
        "`main` (`sbt +dependencyExport`, then `security/dependencies.py --write`).",
        "",
        "## The daily scan",
        "",
        "The `Vulnerability scan` workflow runs every day and on every change to the build: it "
        "resolves the dependencies of every published module for every Scala version, adds the "
        "libraries that jars bundle under their own name, and asks [OSV.dev](https://osv.dev) "
        "(GitHub advisories, CVEs) about each artifact - under every name an advisory may be "
        "filed for it (a Scala library's advisories are filed per Scala suffix, not always for "
        "all of them). It scans `main` and, from 1.3.0 on, the latest release - what users "
        "install - and publishes the result for the README badges (branch `security-status`).",
        "",
        "An advisory that is not in the accepted list below fails the run and opens an issue: "
        "the dependency is upgraded, or - only when no fixed version can be used - the advisory "
        "is accepted with its status and reason. A run that could not reach OSV.dev fails "
        "without an issue.",
        "",
        "Bundled libraries: Play WS's shaded AsyncHttpClient and Netty and jars with embedded "
        "Maven metadata are read from the jars themselves; for the others (Akka's and Pekko's "
        "shaded protobuf, Play WS's shaded OAuth signer) the script holds one rule per jar "
        "version, taken from that jar's build. A jar with relocated or vendored packages that "
        "no rule covers fails the check. A source copy without upstream coordinates is listed "
        "as such and cannot be scanned.",
        "",
        "Run it locally:",
        "",
        "```",
        "sbt +dependencyExport",
        "security/dependencies.py            # the check CI runs",
        "security/dependencies.py --write    # after a dependency change: regenerate the tables",
        "```",
        "",
        "## Accepted advisories",
        "",
        "Advisories that match a dependency's version and are accepted "
        "(`security/accepted.json`): not reachable - the vulnerable code is never used; closed "
        "in ws-client - the engine's own code prevents it; or open - not closed, with what it "
        "takes and the way out. An entry covers exactly its artifact, versions and advisories: a "
        "new advisory for the same artifact fails the scan. All of them are in libraries that "
        "Play WS and Akka bundle (shaded), in their newest releases.",
        "",
    ]
    lines += accepted_table()
    lines += ["", "## Libraries", ""]
    lines += matrix(report)
    lines += ["", "## Per module", ""]
    lines += module_tables(report)
    return "\n".join(lines).rstrip("\n") + "\n"


def current_readme_section():
    with open(README, encoding="utf-8") as source:
        text = source.read()
    if START not in text or END not in text:
        raise Failed(EXIT_REPOSITORY, f"README.md has no {START} ... {END} section")
    return text, text[text.index(START): text.index(END) + len(END)]


# ----------------------------------------------------------------------------------- scan


def call(path, body=None):
    """One OSV.dev request, retried - a failure is an infrastructure error, not a finding."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    last = None
    for attempt in range(4):
        if attempt:
            time.sleep(5 * attempt)
        try:
            request = urllib.request.Request(
                OSV + path, data=data, headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, ConnectionError, ValueError) as error:
            last = error
    raise Failed(EXIT_INFRASTRUCTURE, f"OSV.dev could not be reached ({path}): {last}")


def query_names(name):
    """Every name an advisory for the artifact may be filed under: a Scala library's
    advisories are filed per Scala suffix (and sometimes without one) - not always for all."""
    base = strip_scala(name)
    return [name] if base == name else [base] + [base + suffix for suffix in SCALA_SUFFIXES]


def query_ids(queries):
    """The advisory ids of each (name, version) query, following OSV's result pages."""
    ids = [set() for _ in queries]
    pending = [(index, None) for index in range(len(queries))]
    while pending:
        batch, pending = pending[:500], pending[500:]
        body = []
        for index, token in batch:
            name, version = queries[index]
            query = {"package": {"ecosystem": "Maven", "name": name}, "version": version}
            if token:
                query["page_token"] = token
            body.append(query)
        results = call("/querybatch", {"queries": body})["results"]
        for (index, _), result in zip(batch, results):
            ids[index].update(vulnerability["id"] for vulnerability in result.get("vulns", []))
            if result.get("next_page_token"):
                pending.append((index, result["next_page_token"]))
    return ids


def scan(report):
    """(artifacts, {(name, version): sorted advisory ids}) for every artifact that has any."""
    artifacts = scannable(report)
    queries, owners = [], []
    for artifact in artifacts:
        for variant in query_names(artifact[0]):
            queries.append((variant, artifact[1]))
            owners.append(artifact)
    found = {}
    for artifact, ids in zip(owners, query_ids(queries)):
        if ids:
            found.setdefault(artifact, set()).update(ids)
    return artifacts, {artifact: sorted(ids) for artifact, ids in found.items()}


def accepted_ids(accepted):
    """{(package, version, advisory id)} of the accepted list."""
    return {
        (entry["package"], version, identifier)
        for entry in accepted
        for version in entry["versions"]
        for identifier in entry["ids"]
    }


def describe(vulnerability):
    severity = vulnerability.get("database_specific", {}).get("severity", "?")
    aliases = [a for a in vulnerability.get("aliases", []) if a.startswith("CVE-")]
    fixed = sorted({
        event["fixed"]
        for affected in vulnerability.get("affected", [])
        for version_range in affected.get("ranges", [])
        for event in version_range.get("events", [])
        if "fixed" in event
    })
    summary = vulnerability.get("summary") or vulnerability.get("details", "")[:120]
    return "{} {} [{}] {} (fixed in: {})".format(
        vulnerability["id"], " ".join(aliases), severity, summary.strip(), ", ".join(fixed) or "no fix"
    )


def users(report, name, version):
    return sorted({
        f"{module}_{scala}"
        for (module, scala), entries in report.items()
        for entry in entries
        if entry["name"] == name and entry["version"] == version
    })


def write_badges(directory, label, artifacts, new, total, by_status, today):
    """shields.io endpoint files (and a status.json) for the README badges."""
    os.makedirs(directory, exist_ok=True)

    def endpoint(name, badge_label, message, color):
        with open(os.path.join(directory, f"{name}.json"), "w", encoding="utf-8") as target:
            json.dump(dict(schemaVersion=1, label=badge_label, message=message, color=color), target)

    clean = new == 0
    if label == "main":
        endpoint("last-scan", "last scan", today, "blue")
        endpoint("new-advisories", "new advisories", str(new), "brightgreen" if clean else "red")
        endpoint("artifacts", "artifacts scanned", str(artifacts), "blue")
        open_ones = by_status.get("residual", 0)
        endpoint(
            "accepted",
            "accepted advisories",
            f"{total} ({open_ones} open)",
            "yellow" if open_ones else "green",
        )
    else:
        endpoint(
            "release",
            f"release {label}",
            "no new advisories" if clean else f"{new} new advisories",
            "brightgreen" if clean else "red",
        )
    with open(os.path.join(directory, f"status-{re.sub(r'[^A-Za-z0-9.]+', '-', label)}.json"), "w", encoding="utf-8") as target:
        json.dump(
            dict(scanned=label, date=today, artifacts=artifacts, newAdvisories=new, accepted=total, byStatus=by_status),
            target,
            indent=2,
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--write", action="store_true", help="rewrite the README section and docs/dependencies.md")
    parser.add_argument("--offline", action="store_true", help="skip the vulnerability scan")
    parser.add_argument("--reports", default=os.path.join(ROOT, "target", "dependency-report"))
    parser.add_argument("--no-tables", action="store_true", help="do not check the generated docs")
    parser.add_argument("--badges", metavar="DIR", help="write the README badges' endpoint files")
    parser.add_argument("--label", default="main", help="names the scanned tree in the badge files")
    arguments = parser.parse_args()

    report, unmapped = load(arguments.reports)
    repository, advisories = list(unmapped), 0

    if arguments.write:
        readme, section = current_readme_section()
        with open(README, "w", encoding="utf-8") as target:
            target.write(readme.replace(section, readme_section(report)))
        with open(PAGE, "w", encoding="utf-8") as target:
            target.write(page(report))
        print(f"Wrote the README section and {os.path.relpath(PAGE, ROOT)}")
    elif not arguments.no_tables:
        _, section = current_readme_section()
        page_text = open(PAGE, encoding="utf-8").read() if os.path.isfile(PAGE) else ""
        if section != readme_section(report) or page_text != page(report):
            repository.append(
                "The dependency tables are out of date - run `security/dependencies.py --write` "
                "and commit README.md and docs/dependencies.md"
            )

    if not arguments.offline:
        accepted = accepted_entries()
        known = accepted_ids(accepted)
        artifacts, found = scan(report)
        new, matched = [], set()
        for (name, version), ids in sorted(found.items()):
            for identifier in ids:
                key = (strip_scala(name), version, identifier)
                if key in known:
                    matched.add(key)
                else:
                    new.append((name, version, identifier))
        advisories = len(new)
        print(f"Scanned {len(artifacts)} artifacts: {len(new)} new advisories, {len(matched)} accepted")
        # an accepted entry that matches nothing any more - only for the tree the list is
        # kept for (a release may lack a dependency main has)
        stale = [] if arguments.no_tables else sorted(known - matched)
        if stale:
            print(f"\n{len(stale)} accepted entries no longer match anything (remove them):")
            for package, version, identifier in stale:
                print(f"  {package} {version} {identifier}")
            repository.append(f"{len(stale)} stale entries in security/accepted.json")
        if new:
            print("\nNEW advisories - upgrade the dependency, or accept with a status and a reason:")
            details = {}
            for name, version, identifier in new:
                if identifier not in details:
                    details[identifier] = call(f"/vulns/{identifier}")
                print(f"\n  {name} {version}")
                print(f"    {describe(details[identifier])}")
                print(f"    https://osv.dev/vulnerability/{identifier}")
                print(f"    in: {', '.join(users(report, name, version))}")
        if arguments.badges:
            total, by_status = accepted_summary()
            write_badges(
                arguments.badges,
                arguments.label,
                len(artifacts),
                len(new),
                total,
                by_status,
                datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
            )

    for problem in repository:
        print(f"\n{problem}")
    if advisories:
        print(f"\nFAILED: {advisories} advisories that are not accepted")
        sys.exit(EXIT_ADVISORY)
    if repository:
        print("\nFAILED: the repository needs an update (see above)")
        sys.exit(EXIT_REPOSITORY)
    print("OK")


if __name__ == "__main__":
    try:
        main()
    except Failed as failure:
        print(f"FAILED: {failure}")
        sys.exit(failure.code)
