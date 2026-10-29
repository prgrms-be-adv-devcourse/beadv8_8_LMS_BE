#!/usr/bin/env python3
"""Local Gateway integration test for the #372 completion criteria. Requires PyYAML, cryptography, websockets. No EC2 access."""

import base64
import copy
import http.client
import json
import pathlib
import socket
import ssl
import subprocess
import tempfile
import time

import yaml
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes
from websockets.sync.client import connect

ROOT = pathlib.Path(__file__).resolve().parents[3]
CTX = "k3d-hapbang"
NS = "hapbang-prod"
EG = "envoy-gateway-system"
LABEL = "hapbang.io/gateway-test"


def kube(*args, stdin=None):
    return subprocess.check_output(
        ["kubectl", "--context", CTX, *args], input=stdin, text=True
    )


def apply(docs):
    for doc in docs:
        doc["metadata"].setdefault("labels", {})[LABEL] = "true"
    kube("apply", "-f", "-", stdin=yaml.safe_dump_all(docs))


def resource(kind, name, spec=None):
    d = {"apiVersion": "v1", "kind": kind, "metadata": {"name": name, "namespace": NS}}
    if spec is not None:
        d["spec"] = spec
    return d


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


keys = {
    kid: rsa.generate_private_key(public_exponent=65537, key_size=2048)
    for kid in ["first", "unpublished"]
}


def jwks(kids):
    entries = []
    for kid in kids:
        n = keys[kid].public_key().public_numbers()
        entries.append(
            {
                "kty": "RSA",
                "alg": "RS256",
                "use": "sig",
                "kid": kid,
                "n": b64(n.n.to_bytes((n.n.bit_length() + 7) // 8, "big")),
                "e": b64(n.e.to_bytes(3, "big")),
            }
        )
    return json.dumps({"keys": entries})


def token(kid="first", issuer="https://hapbang.local", expired=False):
    payload = {
        "sub": "42",
        "role": "USER",
        "iss": issuer,
        "exp": int(time.time()) + (-3600 if expired else 600),
    }
    data = (
        b64(json.dumps({"alg": "RS256", "kid": kid}).encode())
        + "."
        + b64(json.dumps(payload).encode())
    )
    return (
        data
        + "."
        + b64(keys[kid].sign(data.encode(), padding.PKCS1v15(), hashes.SHA256()))
    )


docs = []
config = resource("ConfigMap", "gateway-test-jwks")
config["data"] = {"jwks.json": jwks(["first"])}
docs.append(config)
for name, pool, labels, serve in [
    (
        "gateway-test-backend",
        "core-data",
        {"app.kubernetes.io/name": "gateway-test-backend"},
        True,
    ),
    (
        "gateway-test-caller",
        "app-observe",
        {"app.kubernetes.io/name": "billing-app"},
        False,
    ),
]:
    pod = resource(
        "Pod",
        name,
        {
            "nodeSelector": {"hapbang.io/pool": pool},
            "containers": [
                {
                    "name": "fixture",
                    "image": "hapbang/gateway-test:372",
                    "imagePullPolicy": "Never",
                    "command": [
                        "sh",
                        "-c",
                        "cp /jwks/jwks.json /tmp/jwks.json; exec python /fixture/backend.py",
                    ]
                    if serve
                    else ["python", "-c", "import time; time.sleep(3600)"],
                    "resources": {
                        "requests": {"memory": "32Mi", "cpu": "10m"},
                        "limits": {"memory": "64Mi"},
                    },
                    **(
                        {
                            "volumeMounts": [
                                {"name": "jwks", "mountPath": "/jwks", "readOnly": True}
                            ]
                        }
                        if serve
                        else {}
                    ),
                }
            ],
            **(
                {
                    "volumes": [
                        {"name": "jwks", "configMap": {"name": "gateway-test-jwks"}}
                    ]
                }
                if serve
                else {}
            ),
        },
    )
    pod["metadata"]["labels"] = labels
    docs.append(pod)
docs.append(
    resource(
        "Service",
        "gateway-test-backend",
        {
            "selector": {"app.kubernetes.io/name": "gateway-test-backend"},
            "ports": [
                {"name": "http", "port": 8080},
                {"name": "internal", "port": 8081},
            ],
        },
    )
)
# Clone real app routes/policies; use only fixture backends. Do not replace running app Deployments or Services.
for app in ("member", "appearance", "lecture", "billing", "chat", "customer-support"):
    rendered = yaml.safe_load_all(
        subprocess.check_output(
            [
                "kubectl",
                "--context",
                CTX,
                "kustomize",
                str(ROOT / f"apps/{app}-app/k8s/overlays/local"),
            ],
            text=True,
        )
    )
    for original in rendered:
        if original["kind"] not in (
            "HTTPRoute",
            "SecurityPolicy",
            "BackendTrafficPolicy",
            "NetworkPolicy",
        ):
            continue
        d = copy.deepcopy(original)
        d["metadata"]["name"] = "gateway-test-" + d["metadata"]["name"]
        if d["kind"] == "HTTPRoute":
            for rule in d["spec"]["rules"]:
                for ref in rule.get("backendRefs", []):
                    ref["name"] = "gateway-test-backend"
        elif d["kind"] == "SecurityPolicy":
            for p in d["spec"]["jwt"]["providers"]:
                p["issuer"] = "https://hapbang.local"
                p["remoteJWKS"]["uri"] = (
                    "http://gateway-test-backend.hapbang-prod.svc.cluster.local:8080/jwks.json"
                )
                p["remoteJWKS"]["cacheDuration"] = "1s"
        elif d["kind"] == "NetworkPolicy":
            if app != "member":
                continue
            d["spec"]["podSelector"]["matchLabels"]["app.kubernetes.io/name"] = (
                "gateway-test-backend"
            )
        for ref in d["spec"].get("targetRefs", []):
            ref["name"] = "gateway-test-" + ref["name"]
        docs.append(d)


class Connection(http.client.HTTPSConnection):
    def connect(self):
        self.sock = self._context.wrap_socket(
            socket.create_connection(("127.0.0.1", 8443), timeout=40),
            server_hostname="hapbang.local",
        )


def check(label, condition):
    if not condition:
        raise AssertionError(label)
    print("PASS", label, flush=True)


def retry(fn, seconds=60):
    deadline = time.monotonic() + seconds
    while True:
        try:
            return fn()
        except (AssertionError, OSError, ValueError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


forward = None
try:
    deployments = json.loads(
        kube(
            "-n",
            EG,
            "get",
            "deployment",
            "-l",
            "gateway.envoyproxy.io/owning-gateway-name=hapbang",
            "-o",
            "json",
        )
    )
    for deployment in deployments["items"]:
        kube(
            "-n",
            EG,
            "rollout",
            "status",
            "deployment/" + deployment["metadata"]["name"],
            "--timeout=180s",
        )
    apply(docs)
    for name in ("backend", "caller"):
        kube(
            "-n",
            NS,
            "wait",
            "--for=condition=Ready",
            "pod/gateway-test-" + name,
            "--timeout=180s",
        )
    kube(
        "-n",
        NS,
        "wait",
        '--for=jsonpath={.status.parents[0].conditions[?(@.type=="Accepted")].status}=True',
        "httproute/gateway-test-member-app",
        "--timeout=120s",
    )
    svcs = json.loads(
        kube(
            "-n",
            EG,
            "get",
            "svc",
            "-l",
            "gateway.envoyproxy.io/owning-gateway-name=hapbang",
            "-o",
            "json",
        )
    )
    svc = next(
        s["metadata"]["name"]
        for s in svcs["items"]
        if any(p["port"] == 443 for p in s["spec"]["ports"])
    )
    forward_command = [
        "kubectl",
        "--context",
        CTX,
        "-n",
        EG,
        "port-forward",
        "svc/" + svc,
        "8443:443",
        "8088:80",
    ]
    forward = subprocess.Popen(
        forward_command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
    )
    with tempfile.TemporaryDirectory(prefix="gateway-372-") as temp:
        cert = pathlib.Path(temp) / "ca.crt"
        secret = json.loads(
            kube("-n", EG, "get", "secret", "hapbang-tls", "-o", "json")
        )
        cert.write_bytes(base64.b64decode(secret["data"]["tls.crt"]))
        context = ssl.create_default_context(cafile=str(cert))

        def request(
            path="/api/v1/members/probe", headers=None, body=None, method="GET"
        ):
            global forward
            if forward.poll() is not None:
                diagnostic = forward.stderr.read().decode().strip().splitlines()
                print(
                    "Local port-forward reconnect:",
                    diagnostic[-1:] or ["closed"],
                    flush=True,
                )
                forward = subprocess.Popen(
                    forward_command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
                )
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with socket.create_connection(("127.0.0.1", 8443), timeout=1):
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise
                        time.sleep(0.1)
            conn = Connection("hapbang.local", 8443, context=context, timeout=40)
            try:
                conn.request(method, path, body=body, headers=headers or {})
                resp = conn.getresponse()
                headers = dict(resp.headers)
                headers["Set-Cookie"] = "\n".join(
                    resp.headers.get_all("Set-Cookie", [])
                )
                return resp.status, headers, resp.read()
            finally:
                conn.close()

        def ready():
            result = request()
            assert result[0] == 200, result
            return result

        retry(ready)
        # 1. A 2GB upload completes (slow, so only with --large-upload).
        if "--large-upload" in __import__("sys").argv:
            size = 2 * 1024**3
            status, _, body = request(
                "/api/v1/members/files/1/content",
                headers={
                    "Content-Type": "application/octet-stream",
                    "Content-Length": str(size),
                },
                body=(b"x" * (1024 * 1024) for _ in range(2048)),
                method="PUT",
            )
            check(
                "2GiB streamed to backend",
                status == 200 and json.loads(body)["received"] == size,
            )
        # 2. External /api/v1/internal/** and a spoofed X-User-Id never reach the app.
        # 3. Expired or forged tokens get 401; requests without a token pass.
        status, _, body = request(
            headers={
                "X-User-Id": "999",
                "X-User-Role": "ADMIN",
                "X-Internal-Api-Key": "spoof",
            }
        )
        received = json.loads(body)["headers"]
        check(
            "missing JWT allowed; spoofed identity/internal key removed",
            status == 200
            and not any(
                k.lower() in ("x-user-id", "x-user-role", "x-internal-api-key")
                for k in received
            ),
        )
        status, _, body = request(
            headers={"Authorization": "Bearer " + token(), "X-User-Id": "999"}
        )
        received = {k.lower(): v for k, v in json.loads(body)["headers"].items()}
        check(
            "verified JWT claims replace client identity",
            status == 200
            and received.get("x-user-id") == "42"
            and received.get("x-user-role") == "USER",
        )
        check(
            "internal API has no external backend",
            request("/api/v1/internal/members")[0] == 404,
        )
        for label, t in [
            ("expired", token(expired=True)),
            ("issuer", token(issuer="https://wrong.local")),
            ("signature", token()[:-8] + "AAAAAAAA"),
            ("unknown kid", token("unpublished")),
        ]:
            result = request(headers={"Authorization": "Bearer " + t})
            check("reject " + label + " status=" + str(result[0]), result[0] == 401)

        # 4. NetworkPolicy blocks non-Envoy pods from the app's public port.
        def caller_public_port():
            script = "import urllib.request,urllib.error,socket; socket.setdefaulttimeout(3);\ntry:\n r=urllib.request.urlopen('http://gateway-test-backend:8080/public',timeout=3); print(r.status)\nexcept urllib.error.HTTPError as e: print(e.code)\nexcept (OSError,urllib.error.URLError): print('blocked')"
            return kube(
                "-n", NS, "exec", "gateway-test-caller", "--", "python", "-c", script
            ).strip()

        retry(
            lambda: check(
                "caller cannot bypass Gateway on public port",
                caller_public_port() == "blocked",
            )
        )
        # 5. A spoofed X-Forwarded-For cannot change the rate limit key.
        before = json.loads(request()[2])["headers"]
        after = json.loads(request(headers={"X-Forwarded-For": "198.51.100.77"})[2])[
            "headers"
        ]

        def getxff(headers):
            value = next(
                v for k, v in headers.items() if k.lower() == "x-forwarded-for"
            )
            return value.split(",")[-1].strip()

        check(
            "client IP rightmost hop cannot be spoofed", getxff(before) == getxff(after)
        )
        # 6. The chat WebSocket connects.
        with connect(
            "wss://hapbang.local:8443/api/v1/chats/ws",
            sock=socket.create_connection(("127.0.0.1", 8443)),
            ssl=context,
            proxy=None,
        ) as ws:
            ws.send("gateway-372")
            check(
                "WebSocket upgrade and bidirectional frame", ws.recv() == "gateway-372"
            )
        # 7. Grafana is reachable from outside only after login.
        check(
            "Grafana rejects anonymous data", request("/grafana/api/search")[0] == 401
        )
        credentials = json.loads(
            kube("-n", "hapbang-platform", "get", "secret", "grafana", "-o", "json")
        )["data"]
        user = base64.b64decode(credentials["admin-user"]).decode()
        password = base64.b64decode(credentials["admin-password"]).decode()
        login_body = json.dumps({"user": user, "password": password}).encode()
        status, headers, _ = request(
            "/grafana/login",
            headers={"Content-Type": "application/json"},
            body=login_body,
            method="POST",
        )
        check("Grafana authenticated login", status == 200)
        cookie = "; ".join(
            line.split(";")[0] for line in headers.get("Set-Cookie", "").splitlines()
        )
        check(
            "Grafana authenticated data",
            request("/grafana/api/search", headers={"Cookie": cookie})[0] == 200,
        )
        print("Gateway integration checks completed", flush=True)
except Exception:
    for kind in (
        "httproute",
        "securitypolicy",
        "backendtrafficpolicy",
    ):
        status = json.loads(
            kube("-n", NS, "get", kind, "-l", LABEL + "=true", "-o", "json")
        )
        print(
            [
                (d["kind"], d["metadata"]["name"], c)
                for d in status["items"]
                for a in d.get("status", {}).get(
                    "ancestors", d.get("status", {}).get("parents", [])
                )
                for c in a.get("conditions", [])
                if c.get("status") != "True"
            ],
            flush=True,
        )
    raise
finally:
    if forward:
        forward.terminate()
        forward.wait(timeout=10)
    kube(
        "-n",
        NS,
        "delete",
        "pod,service,configmap,httproute,securitypolicy,backendtrafficpolicy,networkpolicy",
        "-l",
        LABEL + "=true",
        "--ignore-not-found=true",
        "--wait=false",
    )
