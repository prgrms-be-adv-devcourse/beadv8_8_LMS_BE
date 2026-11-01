#!/usr/bin/env python3
"""Render to stdout only. Does not apply, mutate overlays or read secrets. Requires PyYAML."""

import argparse
import os
import pathlib
import subprocess
import sys
from urllib.parse import urlparse
import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--gateway-only", action="store_true",
    help="Render shared Gateway resources without app overlays",
)
parser.add_argument(
    "--without-acme-email", action="store_true",
    help="Explicitly register ACME accounts without a contact email",
)
args = parser.parse_args()
names = ("GATEWAY_HOST", "ACME_EMAIL", "JWT_ISSUER", "MEMBER_JWKS_URI")
values = {name: os.environ.get(name, "") for name in names}
required = names[:2] if args.gateway_only else names
if args.without_acme_email:
    if values["ACME_EMAIL"]:
        parser.error("Do not set ACME_EMAIL with --without-acme-email")
    required = tuple(name for name in required if name != "ACME_EMAIL")
for name in required:
    value = values[name]
    if not value or "invalid" in value or any(c.isspace() for c in value):
        sys.exit(f"Set {name} to an explicit production value")
if "/" in values["GATEWAY_HOST"] or ":" in values["GATEWAY_HOST"]:
    sys.exit("GATEWAY_HOST must be a DNS hostname without scheme/port")
if not args.gateway_only and urlparse(values["JWT_ISSUER"]).scheme != "https":
    sys.exit("JWT_ISSUER must use HTTPS")
jwks = urlparse(values["MEMBER_JWKS_URI"])
if not args.gateway_only and (
    jwks.scheme != "http"
    or jwks.hostname != "member-app.hapbang-prod.svc.cluster.local"
    or jwks.port != 8080
    or jwks.username
    or jwks.password
    or jwks.query
    or jwks.fragment
):
    sys.exit(
        "MEMBER_JWKS_URI must target member-app HTTP port 8080 without credentials/query"
    )
paths = ["k8s/gateway/overlays/prod"]
if not args.gateway_only:
    paths += sorted(str(p) for p in pathlib.Path("apps").glob("*-app/k8s/overlays/prod"))
docs = []
for path in paths:
    docs.extend(
        yaml.safe_load_all(
            subprocess.check_output(
                ["kubectl", "--context", "hapbang-k3s", "kustomize", path], text=True
            )
        )
    )
for d in docs:
    # The root route targets the FE Service; install it with the apps, not beforehand.
    if (
        args.gateway_only and d["kind"] == "HTTPRoute"
        and d["metadata"].get("namespace") == "hapbang-prod"
    ):
        continue
    spec = d.get("spec", {})
    if d["kind"] == "Gateway":
        for listener in spec["listeners"]:
            listener["hostname"] = values["GATEWAY_HOST"]
    elif d["kind"] == "Certificate":
        spec["dnsNames"] = [values["GATEWAY_HOST"]]
    elif d["kind"] == "ClusterIssuer":
        if args.without_acme_email:
            spec["acme"].pop("email", None)
        else:
            spec["acme"]["email"] = values["ACME_EMAIL"]
    elif d["kind"] == "SecurityPolicy":
        for provider in spec.get("jwt", {}).get("providers", []):
            provider["issuer"] = values["JWT_ISSUER"]
            provider["remoteJWKS"]["uri"] = values["MEMBER_JWKS_URI"]
    elif d["kind"] == "HelmChartConfig" and d["metadata"]["name"] == "grafana":
        spec["valuesContent"] = spec["valuesContent"].replace(
            "gateway.invalid", values["GATEWAY_HOST"]
        )
    yaml.safe_dump(d, sys.stdout, explicit_start=True, sort_keys=False, allow_unicode=True)
