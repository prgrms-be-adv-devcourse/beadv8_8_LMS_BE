#!/usr/bin/env python3
"""Check the JWT settings of every app prod overlay (#500). Requires PyYAML.

member-app writes HAPBANG_JWT_ISSUER into the tokens it issues, and Envoy accepts only
the issuer in each app's SecurityPolicy. A mismatch turns every token request of that
app into 401, so CI fails before merge instead. Envoy must also read the JWKS from the
member-app external port inside the cluster (the check render-prod.py used to do).
"""

import pathlib
import subprocess
import sys
from urllib.parse import urlparse

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
ISSUER_KEY = "HAPBANG_JWT_ISSUER"


def render(path):
    out = subprocess.check_output(["kubectl", "kustomize", str(path)], text=True)
    return [d for d in yaml.safe_load_all(out) if d]


errors = []
issuers = {}  # "<app> SecurityPolicy <name>" or "member-app ConfigMap" -> issuer
overlays = sorted(ROOT.glob("apps/*-app/k8s/overlays/prod"))
if not overlays:
    sys.exit("no app prod overlays found")

for overlay in overlays:
    app = overlay.parts[-4]
    docs = render(overlay)
    providers = [
        (d["metadata"]["name"], p)
        for d in docs
        if d["kind"] == "SecurityPolicy"
        for p in d.get("spec", {}).get("jwt", {}).get("providers", [])
    ]
    if not providers:
        errors.append(f"{app}: prod overlay has no SecurityPolicy JWT provider")
    for name, provider in providers:
        issuers[f"{app} SecurityPolicy {name}"] = provider.get("issuer")
        jwks = urlparse(provider.get("remoteJWKS", {}).get("uri", ""))
        if (
            jwks.scheme != "http"
            or jwks.hostname != "member-app.hapbang-prod.svc.cluster.local"
            or jwks.port != 8080
            or jwks.username
            or jwks.password
            or jwks.query
            or jwks.fragment
        ):
            errors.append(
                f"{app} SecurityPolicy {name}: remoteJWKS must target member-app HTTP port 8080"
                " without credentials/query"
            )
    if app == "member-app":
        values = [
            d.get("data", {}).get(ISSUER_KEY)
            for d in docs
            if d["kind"] == "ConfigMap" and d["metadata"]["name"].startswith("member-app-config")
        ]
        if not any(values):
            errors.append(f"member-app: prod overlay ConfigMap has no {ISSUER_KEY}")
        for value in values:
            if value:
                issuers[f"member-app ConfigMap {ISSUER_KEY}"] = value

distinct = set(issuers.values())
if len(distinct) > 1:
    errors.append("issuers differ:\n" + "\n".join(f"  {k}: {v}" for k, v in sorted(issuers.items())))
for value in distinct:
    if not value or "invalid" in value or urlparse(value).scheme != "https":
        errors.append(f"issuer must be an explicit https production value: {value!r}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"JWT issuer {distinct.pop()} ({len(issuers)} places, {len(overlays)} apps)")
