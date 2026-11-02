#!/usr/bin/env python3
"""Render to stdout only. Does not apply, mutate overlays or read secrets. Requires PyYAML.

Renders the shared Gateway resources only. App overlays carry their own production
JWT issuer and are applied by the app deploy script, not by this renderer (#500).
"""

import argparse
import os
import subprocess
import sys
import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--without-acme-email", action="store_true",
    help="Explicitly register ACME accounts without a contact email",
)
args = parser.parse_args()
names = ("GATEWAY_HOST", "ACME_EMAIL")
values = {name: os.environ.get(name, "") for name in names}
required = names
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
docs = yaml.safe_load_all(
    subprocess.check_output(
        ["kubectl", "--context", "hapbang-k3s", "kustomize", "k8s/gateway/overlays/prod"], text=True
    )
)
for d in docs:
    # The root route targets the FE Service; install it with the apps, not beforehand.
    if d["kind"] == "HTTPRoute" and d["metadata"].get("namespace") == "hapbang-prod":
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
    elif d["kind"] == "HelmChartConfig" and d["metadata"]["name"] == "grafana":
        spec["valuesContent"] = spec["valuesContent"].replace(
            "gateway.invalid", values["GATEWAY_HOST"]
        )
    yaml.safe_dump(d, sys.stdout, explicit_start=True, sort_keys=False, allow_unicode=True)
