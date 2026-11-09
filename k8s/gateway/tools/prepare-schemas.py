#!/usr/bin/env python3
"""Extract schemas from the exact upstream releases used by HelmChart (PyYAML required)."""

import http.client
import json
import pathlib
import sys
import time
import urllib.error
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


ATTEMPTS = 5


def fetch(url, attempts=ATTEMPTS, delay=5.0):
    """GitHub 다운로드가 504로 실패한 적이 있어(2026-11-09, #792) 5xx·429·연결 오류는 다시 시도한다. 404 등은 바로 실패한다."""
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code < 500 and error.code != 429:
                raise
            reason = f"HTTP {error.code}"
        except (urllib.error.URLError, TimeoutError, http.client.IncompleteRead, ConnectionError) as error:
            reason = type(error).__name__
        if attempt == attempts:
            raise RuntimeError(f"{url}: {attempts}번 시도해도 받지 못했다({reason})")
        print(f"{url}: {reason}, {delay * attempt:.0f}초 뒤 다시 시도 ({attempt}/{attempts})", file=sys.stderr)
        time.sleep(delay * attempt)


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
    documents = list(yaml.safe_load_all(fetch(url)))
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
