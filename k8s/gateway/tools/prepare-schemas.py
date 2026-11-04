#!/usr/bin/env python3
"""Extract schemas from the exact upstream releases used by HelmChart (PyYAML required)."""

import json
import pathlib
import sys
import urllib.request

import yaml

ENVOY = "v1.9.3"
CERT_MANAGER = "v1.21.3"
KINDS = (
    "envoyproxies",
    "securitypolicies",
    "clienttrafficpolicies",
    "backendtrafficpolicies",
    "envoyextensionpolicies",
    "httproutefilters",
)
urls = [
    f"https://raw.githubusercontent.com/envoyproxy/gateway/{ENVOY}/charts/gateway-helm/charts/crds/crds/"
    f"generated/gateway.envoyproxy.io_{kind}.yaml"
    for kind in KINDS
]
urls.append(
    f"https://raw.githubusercontent.com/envoyproxy/gateway/{ENVOY}/charts/gateway-helm/charts/crds/crds/gatewayapi-crds.yaml"
)
urls.append(
    f"https://github.com/cert-manager/cert-manager/releases/download/{CERT_MANAGER}/cert-manager.crds.yaml"
)


def strict(schema):
    if isinstance(schema, dict):
        if "properties" in schema and not schema.get(
            "x-kubernetes-preserve-unknown-fields"
        ):
            schema.setdefault("additionalProperties", False)
        for value in schema.values():
            strict(value)
    elif isinstance(schema, list):
        for value in schema:
            strict(value)


output = pathlib.Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
count = 0
for url in urls:
    with urllib.request.urlopen(url, timeout=30) as response:
        documents = list(yaml.safe_load_all(response.read()))
    for document in documents:
        if not document or document.get("kind") != "CustomResourceDefinition":
            continue
        spec = document["spec"]
        for version in spec["versions"]:
            schema = version["schema"]["openAPIV3Schema"]
            strict(schema)
            # kubeconform은 ResourceKind를 소문자로 찾는다. macOS는 대소문자를 구분하지 않아 로컬에서 드러나지 않는다.
            name = f"{spec['group']}/{spec['names']['kind'].lower()}_{version['name']}.json"
            path = output / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(schema))
            count += 1
print(f"Prepared {count} schemas from Envoy {ENVOY} and cert-manager {CERT_MANAGER}")
