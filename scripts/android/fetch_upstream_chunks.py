#!/usr/bin/env python3
"""Fetch and localize the published boi-portable browser game for private APK builds.

No Ghidra, game executable, Emscripten or extra Python packages are needed.
The upstream HTML contains archive indexes, scrambling keys and pinned stream
versions. This program edits its source URLs and disables the online mod
catalogue; all archived game bytes remain unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = "chiikabu/boi-portable"
HTML_COMMIT = "bc453b0653de52421a5e4271102f41126a9311ae"
HTML_URL = f"https://raw.githubusercontent.com/{REPO}/{HTML_COMMIT}/index.html"
LOCAL_BASE = "https://appassets.androidplatform.net/payload/c"
DATA_PREFIX = "window.__isaacPortableData = "
CDN_BASE = re.compile(r"^https://cdn\.jsdelivr\.net/gh/chiikabu/boi-portable@([0-9a-f]{7,40})/c$")
MOD_CATALOGUE = re.compile(r'<script>window\.isaacModCatalogue\s*=\s*"[^"]+";</script>')
MAX_CHUNK = 25 * 1024 * 1024


def parse_page(html: str) -> tuple[dict, int, int]:
    start = html.find(DATA_PREFIX)
    if start < 0:
        raise ValueError("Missing portable metadata in upstream HTML")
    start += len(DATA_PREFIX)
    end = html.find(";</script>", start)
    if end < 0:
        raise ValueError("Unterminated portable metadata")
    meta = json.loads(html[start:end])
    if not isinstance(meta, dict) or not isinstance(meta.get("streams"), list):
        raise ValueError("Invalid portable schema")
    if not meta.get("key") or "boot.wasm" not in meta.get("files", {}):
        raise ValueError("Missing the embedded WASM mapping or key")
    return meta, start, end


def pinned_chunks(meta: dict) -> list[tuple[str, str, int]]:
    streams = meta["streams"]
    if len(streams) != 2 or [s.get("tag") for s in streams] != ["a", "b"]:
        raise ValueError("Expected two streams named a and b")
    if [s.get("n") for s in streams] != [4, 28]:
        raise ValueError("Expected 4 a-chunks and 28 b-chunks")
    result = []
    for stream in streams:
        base = stream.get("base", meta.get("base", ""))
        if not CDN_BASE.fullmatch(base):
            raise ValueError(f"Unexpected or unpinned source URL: {base}")
        sizes = stream.get("stored", [])
        if len(sizes) != stream["n"]:
            raise ValueError("Invalid chunk sizes array")
        for i, size in enumerate(sizes):
            if not isinstance(size, int) or not 0 < size < MAX_CHUNK:
                raise ValueError("Bad chunk size")
            name = f"{stream['tag']}{i}.bin"
            result.append((name, f"{base}/{name}", size))
    return result


# Original browser build validates the HTTP Content-Length header before reading.
# Android WebView can synthesize or change this header for WebResourceResponse,
# even for a complete InputStream. For our exact same-origin, in-APK chunks,
# validate the status and full-range semantics here; the upstream body() code
# still verifies that precisely the advertised number of bytes were read.
UPSTREAM_WHOLE_HEADER = """  function wholeHeader(r, size) {
    // Fetch exposes decoded bytes, but encoded responses retain their wire length.
    var encoding = (r.headers.get('content-encoding') || 'identity').trim().toLowerCase();
    return r.status === 200 && !r.headers.get('content-range')
      && (encoding !== 'identity' || lengthHeader(r, size));
  }"""
OFFLINE_WHOLE_HEADER = """  function wholeHeader(r, size) {
    // Android's intercepted WebResourceResponse may expose a synthetic or
    // inaccurate Content-Length even when the full asset bytes are readable.
    // Only bypass that HEADER check for this APK's own virtual asset origin.
    // body() still validates the precise expected byte count before accepting.
    if (r.url && r.url.startsWith('https://appassets.androidplatform.net/payload/c/')) {
      var contentRange = r.headers.get('content-range');
      if (r.status === 200 && !contentRange) return true;
      if (r.status === 206 &&
          contentRange === 'bytes 0-' + (size - 1) + '/' + size) return true;
      return false;
    }
    var encoding = (r.headers.get('content-encoding') || 'identity').trim().toLowerCase();
    return r.status === 200 && !r.headers.get('content-range')
      && (encoding !== 'identity' || lengthHeader(r, size));
  }"""
UPSTREAM_WHOLE_FAILURE = "throw new Error('piece ' + s + ':' + i + ': invalid whole response');"
OFFLINE_WHOLE_FAILURE = (
    "throw new Error('piece ' + s + ':' + i + ': invalid whole response'"
    " + ' (HTTP ' + r.status + ', length='"
    " + r.headers.get('content-length') + ', range='"
    " + r.headers.get('content-range') + ', expected=' + size + ')');"
)


def patch_webview_headers(html: str) -> str:
    if html.count(UPSTREAM_WHOLE_HEADER) != 1:
        raise ValueError("Upstream wholeHeader implementation changed; review offline compatibility patch")
    if html.count(UPSTREAM_WHOLE_FAILURE) != 1:
        raise ValueError("Upstream whole-response error branch changed; review diagnostic patch")
    return html.replace(UPSTREAM_WHOLE_HEADER, OFFLINE_WHOLE_HEADER, 1).replace(
        UPSTREAM_WHOLE_FAILURE, OFFLINE_WHOLE_FAILURE, 1
    )


def localize(html: str, meta: dict, begin: int, end: int) -> str:
    meta["base"] = LOCAL_BASE
    for stream in meta["streams"]:
        stream.pop("base", None)
    html = html[:begin] + json.dumps(meta, separators=(",", ":")) + html[end:]
    html, n = MOD_CATALOGUE.subn(
        "<script>window.isaacModCatalogue = null;</script>", html, count=1
    )
    if n != 1:
        raise ValueError("Cannot disable optional online mod catalogue")
    if "https://cdn.jsdelivr.net/gh/chiikabu/boi-portable@" in html:
        raise ValueError("External game resource URL remains")
    return patch_webview_headers(html)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as inp:
        while block := inp.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, size: int, destination: Path) -> str:
    if destination.is_file() and destination.stat().st_size == size:
        return sha256_file(destination)
    partial = destination.with_suffix(".part")
    for attempt in range(1, 5):
        partial.unlink(missing_ok=True)
        total = 0
        digest = hashlib.sha256()
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": "isaac-private-apk/1", "Accept-Encoding": "identity"}
            )
            with urllib.request.urlopen(req, timeout=90) as response:
                if response.status != 200:
                    raise ValueError(f"Unexpected HTTP {response.status}")
                if response.headers.get("Content-Encoding", "identity") != "identity":
                    raise ValueError("Unexpected content encoding for raw .bin")
                with partial.open("wb") as dest:
                    while block := response.read(256 * 1024):
                        dest.write(block)
                        digest.update(block)
                        total += len(block)
                        if total > size:
                            raise ValueError("Server sent more bytes than expected")
            if total != size:
                raise ValueError(f"Truncated chunk: got {total}, expected {size}")
            partial.replace(destination)
            return digest.hexdigest()
        except (OSError, ValueError, urllib.error.URLError) as error:
            partial.unlink(missing_ok=True)
            print(f"Attempt {attempt}/4 failed for {destination.name}: {error}", flush=True)
            if attempt == 4:
                raise
            time.sleep(attempt * 2)
    raise RuntimeError("Cannot download")


def verify(destination: Path) -> None:
    doc = json.loads((destination / "source-manifest.json").read_text("utf-8"))
    page = (destination / "index.html").read_text("utf-8")
    meta, _, _ = parse_page(page)
    if meta.get("base") != LOCAL_BASE or any(s.get("base") for s in meta["streams"]):
        raise ValueError("Page contains a non-local resource base")
    if "window.isaacModCatalogue = null;" not in page:
        raise ValueError("Remote mod catalogue is not disabled")
    if page.count(OFFLINE_WHOLE_HEADER) != 1:
        raise ValueError("Missing WebView local Content-Length compatibility patch")
    if OFFLINE_WHOLE_FAILURE not in page:
        raise ValueError("Missing diagnostic output for intercepted HTTP responses")
    assets = doc.get("files", [])
    if len(assets) != 32 or len({f["name"] for f in assets}) != 32:
        raise ValueError("Must provide exactly 32 distinct chunks")
    for asset in assets:
        name = asset["name"]
        if not re.fullmatch(r"(?:a[0-3]|b(?:[0-9]|1[0-9]|2[0-7]))\.bin", name):
            raise ValueError("Unrecognized payload filename")
        path = destination / "c" / name
        if not path.is_file() or path.stat().st_size != asset["bytes"]:
            raise ValueError(f"Missing or corrupt chunk: {path}")
        if sha256_file(path) != asset["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {path}")
    print("Verified offline index and 32 local chunks.", flush=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, default=Path(".scratch/boi-portable"))
    p.add_argument("--verify-only", action="store_true")
    a = p.parse_args()
    destination = a.out.resolve()
    if a.verify_only:
        verify(destination)
        return
    request = urllib.request.Request(
        HTML_URL, headers={"User-Agent": "isaac-private-apk/1"}
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        html = response.read(2 * 1024 * 1024).decode("utf-8")
    meta, start, end = parse_page(html)
    assets = pinned_chunks(meta)
    page = localize(html, meta, start, end)
    (destination / "c").mkdir(parents=True, exist_ok=True)
    downloaded = []
    for i, (name, url, size) in enumerate(assets, 1):
        print(f"[{i}/{len(assets)}] {name}: {size} bytes", flush=True)
        digest = download(url, size, destination / "c" / name)
        downloaded.append({"name": name, "bytes": size, "url": url, "sha256": digest})
    (destination / "index.html").write_text(page, encoding="utf-8")
    (destination / "source-manifest.json").write_text(
        json.dumps({"html": HTML_URL, "files": downloaded}, indent=2) + "\n",
        encoding="utf-8",
    )
    verify(destination)
    print(f"Ready: {sum(item['bytes'] for item in downloaded)} bytes", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, urllib.error.URLError) as exc:
        print(f"Offline game preparation failed: {exc}", file=sys.stderr)
        sys.exit(1)
