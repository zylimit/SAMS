#!/usr/bin/env python3
"""Populate ~/.m2 by downloading POMs/JARs with curl (Java HTTP to mirrors hangs)."""
from __future__ import annotations

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
M2 = Path.home() / ".m2" / "repository"
MIRRORS = [
    "https://maven.aliyun.com/repository/public",
    "https://repo.huaweicloud.com/repository/maven",
    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
    "https://repo.osgeo.org/repository/release",
    "https://repo1.maven.org/maven2",
]
# Keep corporate curl proxy (Java HTTP hangs on it; curl does not).


def gpath(g: str, a: str, v: str) -> Path:
    return M2 / g.replace(".", "/") / a / v


def curl(url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    r = subprocess.run(
        [
            "curl", "-fL",
            "--connect-timeout", "10", "--max-time", "40",
            "--retry", "1", "-o", str(tmp), url,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if r.returncode == 0 and tmp.stat().st_size > 0:
        tmp.replace(dest)
        return True
    if tmp.exists():
        tmp.unlink()
    return False


def fetch(g: str, a: str, v: str, ext: str) -> Path | None:
    dest = gpath(g, a, v) / f"{a}-{v}.{ext}"
    if dest.is_file() and dest.stat().st_size > 40:
        return dest
    rel = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"
    for base in MIRRORS:
        if curl(f"{base}/{rel}", dest):
            print(f"  ok {g}:{a}:{v}:{ext}", flush=True)
            return dest
    print(f"  MISS {g}:{a}:{v}:{ext}", flush=True)
    return None


def text(el, tag, default=""):
    n = el.find(f"m:{tag}", NS) if el is not None else None
    return n.text.strip() if n is not None and n.text else default


def props_of(root) -> dict[str, str]:
    out = {}
    p = root.find("m:properties", NS)
    if p is None:
        return out
    for c in list(p):
        k = c.tag.split("}")[-1]
        if c.text:
            out[k] = c.text.strip()
    out.setdefault("project.groupId", text(root, "groupId"))
    out.setdefault("project.artifactId", text(root, "artifactId"))
    out.setdefault("project.version", text(root, "version") or text(root.find("m:parent", NS), "version"))
    parent = root.find("m:parent", NS)
    if parent is not None:
        out.setdefault("project.parent.version", text(parent, "version"))
    return out


def subst(s: str, props: dict[str, str]) -> str:
    if not s:
        return s
    for _ in range(8):
        n = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), s)
        if n == s:
            return n
        s = n
    return s


def parse_pom(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except Exception:
        return None


dm: dict[tuple[str, str], tuple[str, str]] = {}  # (g,a) -> (version, type)
seen: set[tuple[str, str, str]] = set()
jars: list[Path] = []


def ingest_pom(g: str, a: str, v: str, root: ET.Element, props: dict[str, str]) -> None:
    # dependencyManagement
    dm_el = root.find("m:dependencyManagement", NS)
    if dm_el is not None:
        deps = dm_el.find("m:dependencies", NS)
        if deps is not None:
            for d in deps.findall("m:dependency", NS):
                dg = subst(text(d, "groupId"), props)
                da = subst(text(d, "artifactId"), props)
                dv = subst(text(d, "version"), props)
                typ = text(d, "type", "jar")
                if dg and da and dv and "${" not in dv:
                    dm[(dg, da)] = (dv, typ)
                    if (typ == "pom" or text(d, "scope") == "import") and da in {
                        "jackson-bom",
                        "spring-framework-bom",
                        "spring-security-bom",
                        "netty-bom",
                        "junit-bom",
                        "log4j-bom",
                        "micrometer-bom",
                        "reactor-bom",
                        "mockito-bom",
                        "assertj-bom",
                    }:
                        ensure(dg, da, dv, "pom", follow=True)


def ensure(g: str, a: str, v: str, want: str, follow: bool) -> None:
    key = (g, a, v)
    if not g or not a or not v or "${" in v:
        return
    pom = fetch(g, a, v, "pom")
    if want == "jar":
        p = fetch(g, a, v, "jar")
        if p:
            jars.append(p)
    if not follow or key in seen or pom is None:
        return
    seen.add(key)
    root = parse_pom(pom)
    if root is None:
        return
    props = props_of(root)
    parent = root.find("m:parent", NS)
    if parent is not None:
        pg, pa, pv = text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version")
        if pg and pa and pv:
            ensure(pg, pa, pv, "pom", follow=True)
            parent_pom = gpath(pg, pa, pv) / f"{pa}-{pv}.pom"
            pr = parse_pom(parent_pom)
            if pr is not None:
                pp = props_of(pr)
                for k, val in pp.items():
                    props.setdefault(k, val)
                ingest_pom(pg, pa, pv, pr, {**pp, **props})
    ingest_pom(g, a, v, root, props)
    deps = root.find("m:dependencies", NS)
    if deps is None:
        return
    for d in deps.findall("m:dependency", NS):
        if text(d, "optional") == "true":
            continue
        scope = text(d, "scope", "compile")
        if scope in {"provided", "system"}:
            continue
        # keep test deps of starter-test only via explicit roots
        if scope == "test" and a not in {"spring-boot-starter-test", "junit-jupiter"}:
            continue
        dg = subst(text(d, "groupId"), props)
        da = subst(text(d, "artifactId"), props)
        dv = subst(text(d, "version"), props)
        typ = text(d, "type", "jar")
        if not dv:
            dv, typ = dm.get((dg, da), ("", typ))
        if typ == "pom":
            ensure(dg, da, dv, "pom", follow=True)
        else:
            ensure(dg, da, dv, "jar", follow=True)


def main() -> int:
    roots = [
        ("org.springframework.boot", "spring-boot-starter-parent", "3.3.5", "pom"),
        ("org.springframework.boot", "spring-boot-dependencies", "3.3.5", "pom"),
        ("org.springframework.boot", "spring-boot-starter-web", "3.3.5", "jar"),
        ("org.springframework.boot", "spring-boot-starter-security", "3.3.5", "jar"),
        ("org.springframework.boot", "spring-boot-starter-validation", "3.3.5", "jar"),
        ("org.springframework.boot", "spring-boot-starter-jdbc", "3.3.5", "jar"),
        ("org.springframework.boot", "spring-boot-starter-test", "3.3.5", "jar"),
        ("com.baomidou", "mybatis-plus-spring-boot3-starter", "3.5.9", "jar"),
        ("com.mysql", "mysql-connector-j", "8.3.0", "jar"),
        ("io.jsonwebtoken", "jjwt-api", "0.11.5", "jar"),
        ("io.jsonwebtoken", "jjwt-impl", "0.11.5", "jar"),
        ("io.jsonwebtoken", "jjwt-jackson", "0.11.5", "jar"),
        ("org.locationtech.jts", "jts-core", "1.19.0", "jar"),
        ("org.wololo", "jts2geojson", "0.18.1", "jar"),
        ("org.apache.poi", "poi-ooxml", "5.2.5", "jar"),
        ("com.deepoove", "poi-tl", "1.12.2", "jar"),
        ("org.junit.platform", "junit-platform-console-standalone", "1.10.5", "jar"),
        ("org.springframework.boot", "spring-boot-maven-plugin", "3.3.5", "jar"),
    ]
    # load BOM first
    ensure("org.springframework.boot", "spring-boot-dependencies", "3.3.5", "pom", True)
    for g, a, v, t in roots:
        print(f"ROOT {g}:{a}:{v}", flush=True)
        ensure(g, a, v, t, True)
    unique = []
    seenp = set()
    for p in jars:
        if p not in seenp:
            seenp.add(p)
            unique.append(p)
    cp_file = Path("/home/z00632348/code/SAMS/backend/.classpath-jars")
    cp_file.write_text("\n".join(str(p) for p in unique) + "\n")
    print(f"jars={len(unique)} written {cp_file}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
