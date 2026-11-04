"""Rebuild the exact BTW profile bridge with pinned Temurin; target Java 17."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
DEPENDENCIES = [
    ("btwce-3.1.1-intermediary.jar", "https://cdn.modrinth.com/data/PiC4CKoa/versions/zLnGXEI4/btwce-3.1.1-intermediary.jar", "253b06d03ec51f799474e5c1bf45a9771ca9f2265d45d3000e465e7122e0ad40"),
    ("mappings-1.0.3.jar", "https://btw-community.github.io/btw-mvn/btw/community/mappings/1.0.3/mappings-1.0.3.jar", "485de82754b8bdd42364f9d29dc42a55ba9e6f8f9a4679958aedefce0579cfcb"),
    ("fabric-loader-0.18.2.jar", "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.18.2/fabric-loader-0.18.2.jar", "3ab7f6b1732170394e77c9f31f8b598928f8766546f9849482431ddbd21f1acd"),
    # Compile-time annotations only; Loader supplies the runtime's own Mixin.
    ("sponge-mixin-0.16.4+mixin.0.8.7.jar", "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.16.4+mixin.0.8.7/sponge-mixin-0.16.4+mixin.0.8.7.jar", "921a2ca066d82d15d2939efd5eb22754e308db74b1e4277130a76625c3638dfd"),
]

def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

def build(pack: Path) -> Path:
    artifacts = ROOT / "artifacts"
    deps, classes = artifacts / "deps", artifacts / "classes"
    deps.mkdir(parents=True, exist_ok=True)
    java_home = os.environ.get("JAVA_HOME", "")
    javac = (Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac") if java_home else Path(shutil.which("javac") or "javac")).resolve()
    java = javac.with_name("java.exe" if os.name == "nt" else "java")
    release = javac.parent.parent / "release"
    compiler = dict(line.split("=", 1) for line in release.read_text(encoding="utf-8").splitlines() if "=" in line)
    if (compiler.get("IMPLEMENTOR", "").strip('"') != "Eclipse Adoptium" or
            compiler.get("JAVA_RUNTIME_VERSION", "").strip('"').removesuffix("-LTS") != "21.0.12.1+1"):
        raise RuntimeError("Reproducible bridge builds require JAVA_HOME=Temurin 21.0.12.1+1; output targets Java 17.")
    for name, url, expected in DEPENDENCIES:
        target = deps / name
        if not target.exists() or digest(target) != expected:
            temporary = target.with_suffix(".download")
            with urllib.request.urlopen(url, timeout=45) as response:
                temporary.write_bytes(response.read())
            if digest(temporary) != expected:
                raise RuntimeError(f"Downloaded dependency SHA256 mismatch: {name}")
            temporary.replace(target)
        if digest(target) != expected:
            raise RuntimeError(f"Dependency SHA256 mismatch: {name}")
    if classes.exists():
        if not classes.resolve().is_relative_to(artifacts.resolve()):
            raise RuntimeError("Unsafe classes output path")
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    classpath = os.pathsep.join(str(deps / item[0]) for item in DEPENDENCIES)
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    subprocess.run([str(javac), "--release", "17", "-encoding", "UTF-8", "-proc:none", "-classpath", classpath, "-d", str(classes), *map(str, sources)], check=True)
    tests = sorted((ROOT / "src/test/java").rglob("*.java"))
    if tests:
        test_classes = artifacts / "test-classes"
        test_classes.mkdir(exist_ok=True)
        subprocess.run([str(javac), "--release", "17", "-encoding", "UTF-8", "-proc:none", "-classpath", str(classes), "-d", str(test_classes), *map(str, tests)], check=True)
        subprocess.run([str(java), "-classpath", os.pathsep.join([str(classes), str(test_classes)]), "org.lanminecraft.btwbridge.RegistryDataTest"], check=True)
    output = pack / "mods/btw-lan-bridge-1.0.0.jar"
    output.parent.mkdir(parents=True, exist_ok=True)
    entries = {str(path.relative_to(classes)).replace("\\", "/"): path.read_bytes() for path in classes.rglob("*.class")}
    resources = ROOT / "src/main/resources"
    entries.update({str(path.relative_to(resources)).replace("\\", "/"): path.read_bytes() for path in resources.rglob("*") if path.is_file()})
    entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\n\r\n"
    entries["LICENSE"] = (ROOT / "LICENSE").read_bytes()
    temporary_jar = artifacts / "btw-lan-bridge.jar.tmp"
    with zipfile.ZipFile(temporary_jar, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, contents in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.create_system = 0
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, contents, compresslevel=9)
    # Keep the last complete output if compilation, download or disk writing fails.
    temporary_jar.replace(output)
    report = {"path": str(output.resolve()), "size": output.stat().st_size, "sha256": digest(output), "javaRelease": 17,
              "compiler": "Eclipse Adoptium Temurin 21.0.12.1+1"}
    (artifacts / "build-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
    return output

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", type=Path, default=ROOT.parent.parent)
    args = parser.parse_args()
    build(args.pack.resolve())
