#!/usr/bin/env python3
"""Check that the prod manifests agree on one production host (#500, #507). Requires PyYAML.

- Gateway prod overlay: the Gateway listener hostnames, the Certificate dnsNames and the
  Grafana root_url use one host, and no `.invalid` placeholder is left. The overlay is
  applied as is, so a placeholder would reach production.
- App prod overlays: member-app writes HAPBANG_JWT_ISSUER into the tokens it issues, and
  Envoy accepts only the issuer in each app's SecurityPolicy. Both must be https://<host>;
  a mismatch turns every token request of that app into 401. Envoy must also read the
  JWKS from the member-app external port inside the cluster.
"""

import pathlib
import subprocess
import sys
from urllib.parse import urlparse

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
GATEWAY_OVERLAY = ROOT / "k8s/gateway/overlays/prod"
ISSUER_KEY = "HAPBANG_JWT_ISSUER"
# Sealed ciphertext is random text and may contain any word.
SECRET_KINDS = {"Secret", "SealedSecret"}


def render(path):
    out = subprocess.check_output(["kubectl", "kustomize", str(path)], text=True)
    return [d for d in yaml.safe_load_all(out) if d]


def label(d):
    return f"{d['kind']} {d['metadata']['name']}"


errors = []

# Gateway prod overlay: one host, no placeholder.
hosts = {}  # "<kind> <name> ..." -> host
for d in render(GATEWAY_OVERLAY):
    if d["kind"] not in SECRET_KINDS and "invalid" in yaml.safe_dump(d):
        errors.append(f"{label(d)}: a .invalid placeholder is left in the prod overlay")
    spec = d.get("spec", {})
    if d["kind"] == "Gateway":
        for listener in spec.get("listeners", []):
            hosts[f"{label(d)} listener {listener['name']}"] = listener.get("hostname")
    elif d["kind"] == "Certificate":
        for name in spec.get("dnsNames", []):
            hosts[f"{label(d)} dnsNames"] = name
    elif d["kind"] == "HelmChartConfig" and d["metadata"]["name"] == "grafana":
        values = yaml.safe_load(spec.get("valuesContent", "")) or {}
        root_url = values.get("grafana.ini", {}).get("server", {}).get("root_url", "")
        hosts[f"{label(d)} root_url"] = urlparse(root_url).hostname
if not hosts:
    errors.append("Gateway prod overlay has no Gateway hostname")
host_values = set(hosts.values())
if len(host_values) > 1:
    errors.append("hosts differ:\n" + "\n".join(f"  {k}: {v}" for k, v in sorted(hosts.items())))
host = host_values.pop() if len(host_values) == 1 else None

# App prod overlays: JWT issuer is https://<host> everywhere, JWKS from member-app 8080.
issuers = {}  # "<app> SecurityPolicy <name>" or "member-app ConfigMap" -> issuer
overlays = sorted(ROOT.glob("apps/*-app/k8s/overlays/prod"))
if not overlays:
    errors.append("no app prod overlays found")

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

if host:
    expected = f"https://{host}"
    wrong = {k: v for k, v in issuers.items() if v != expected}
    if wrong:
        errors.append(
            f"JWT issuer must be {expected} (the Gateway host):\n"
            + "\n".join(f"  {k}: {v}" for k, v in sorted(wrong.items()))
        )

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(
    f"prod host {host}: Gateway {len(hosts)} places, JWT issuer {len(issuers)} places"
    f" ({len(overlays)} apps)"
)
