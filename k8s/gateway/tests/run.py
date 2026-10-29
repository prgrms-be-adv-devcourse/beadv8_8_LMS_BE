#!/usr/bin/env python3
"""Local Gateway integration test. Requires PyYAML, cryptography, websockets. No EC2 access."""

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
import uuid

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
    for kid in ["first", "second", "unpublished"]
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
    (
        "gateway-test-outsider",
        "app-observe",
        {"app.kubernetes.io/name": "gateway-test-outsider"},
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
            ["kubectl", "kustomize", str(ROOT / f"apps/{app}-app/k8s/overlays/local")],
            text=True,
        )
    )
    for original in rendered:
        if original["kind"] not in (
            "HTTPRoute",
            "SecurityPolicy",
            "BackendTrafficPolicy",
            "EnvoyExtensionPolicy",
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
    for name in ("backend", "caller", "outsider"):
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
        check("TLS hostname and local certificate verification", True)
        h = http.client.HTTPConnection("127.0.0.1", 8088, timeout=10)
        h.request("GET", "/api/v1/members/probe", headers={"Host": "hapbang.local"})
        r = h.getresponse()
        check(
            "HTTP redirects to HTTPS",
            r.status == 301
            and r.getheader("location").startswith("https://hapbang.local"),
        )
        r.read()
        h.close()
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
        for label, t in [
            ("expired", token(expired=True)),
            ("issuer", token(issuer="https://wrong.local")),
            ("signature", token()[:-8] + "AAAAAAAA"),
            ("unknown kid", token("unpublished")),
        ]:
            result = request(headers={"Authorization": "Bearer " + t})
            check("reject " + label + " status=" + str(result[0]), result[0] == 401)
        for module in (
            "members",
            "appearances",
            "lectures",
            "wallets",
            "settlements",
            "chats",
            "inquiries",
        ):
            check("route " + module, request("/api/v1/" + module + "/probe")[0] == 200)
        check(
            "internal API has no external backend",
            request("/api/v1/internal/members")[0] == 404,
        )
        check("unknown API cannot reach SPA", request("/api/v1/unknown")[0] == 404)
        for headers, expected in [
            ({"Content-Type": "multipart/form-data", "Content-Length": "0"}, 415),
            (
                {
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Content-Length": "0",
                },
                415,
            ),
            ({"Content-Length": "2147483649"}, 413),
        ]:
            result = request(
                "/api/v1/members/files/1/content",
                headers=headers,
                body=b"",
                method="PUT",
            )
            check(
                "upload header guard " + str(expected) + " got " + str(result[0]),
                result[0] == expected,
            )
        check(
            "chunked upload without length rejected",
            request("/api/v1/members/files/1/content", body=iter([b"x"]), method="PUT")[
                0
            ]
            == 411,
        )
        check(
            "upload method guard",
            request(
                "/api/v1/members/files/1/content",
                headers={"Content-Length": "0"},
                body=b"",
                method="POST",
            )[0]
            == 403,
        )
        check(
            "ordinary API body limit",
            request(
                headers={"Content-Length": str(1024 * 1024 + 1)},
                body=b"x" * (1024 * 1024 + 1),
                method="POST",
            )[0]
            == 413,
        )
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

        def from_pod(pod, port, path, key=""):
            script = "import urllib.request,urllib.error,socket; socket.setdefaulttimeout(3); req=urllib.request.Request('http://gateway-test-backend:PORT/PATH',headers={'X-Internal-Api-Key':'KEY'});\ntry:\n r=urllib.request.urlopen(req,timeout=3); print(r.status)\nexcept urllib.error.HTTPError as e: print(e.code)\nexcept (OSError,urllib.error.URLError): print('blocked')"
            script = (
                script.replace("PORT", str(port))
                .replace("PATH", path.lstrip("/"))
                .replace("KEY", key)
            )
            return kube(
                "-n", NS, "exec", "gateway-test-" + pod, "--", "python", "-c", script
            ).strip()

        retry(
            lambda: check(
                "allowed caller internal API",
                from_pod("caller", 8081, "/api/v1/internal/probe", "fixture-key")
                == "200",
            )
        )
        check(
            "internal key required",
            from_pod("caller", 8081, "/api/v1/internal/probe") == "401",
        )
        check(
            "internal listener rejects public path",
            from_pod("caller", 8081, "/public", "fixture-key") == "404",
        )
        check(
            "caller cannot bypass Gateway on public port",
            from_pod("caller", 8080, "/public") == "blocked",
        )
        check(
            "unrelated pod cannot access internal port",
            from_pod("outsider", 8081, "/api/v1/internal/probe", "fixture-key")
            == "blocked",
        )
        jwks_data = jwks(["first", "second"])
        kube(
            "-n",
            NS,
            "exec",
            "-i",
            "gateway-test-backend",
            "--",
            "python",
            "-c",
            "import sys; open('/tmp/jwks.json','w').write(sys.stdin.read())",
            stdin=jwks_data,
        )
        retry(
            lambda: check(
                "JWKS key rotation",
                request(headers={"Authorization": "Bearer " + token("second")})[0]
                == 200,
            )
        )
        kube(
            "-n", NS, "exec", "gateway-test-backend", "--", "touch", "/tmp/disable-jwks"
        )

        def jwks_failed():
            assert (
                request(headers={"Authorization": "Bearer " + token("unpublished")})[0]
                != 200
            )

        retry(jwks_failed)
        check("JWKS failure cannot authenticate unknown key", True)
        check("Grafana subpath redirect", request("/grafana")[0] == 301)
        check("Grafana login page", request("/grafana/login")[0] == 200)
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
        cookie = headers.get("Set-Cookie", "")
        check(
            "Grafana secure session cookie",
            "Secure" in cookie and "grafana_session=" in cookie,
        )
        cookie = "; ".join(line.split(";")[0] for line in cookie.splitlines())
        check(
            "Grafana authenticated data",
            request("/grafana/api/search", headers={"Cookie": cookie})[0] == 200,
        )
        with connect(
            "wss://hapbang.local:8443/grafana/api/live/ws",
            sock=socket.create_connection(("127.0.0.1", 8443)),
            ssl=context,
            additional_headers={"Cookie": cookie},
            proxy=None,
        ) as ws:
            ws.send(json.dumps({"id": 1, "connect": {}}))
            check("Grafana Live WebSocket", bool(ws.recv(timeout=10)))
        # Query Tempo through the authenticated Grafana datasource proxy, not an externally exposed Tempo port.
        trace_id = uuid.uuid4().hex
        request(headers={"traceparent": "00-" + trace_id + "-0123456789abcdef-01"})

        def trace_received():
            status, _, data = request(
                "/grafana/api/datasources/proxy/uid/tempo/api/traces/" + trace_id,
                headers={"Cookie": cookie},
            )
            assert status == 200, (status, data[:200])
            assert json.loads(data), data[:200]

        retry(trace_received, seconds=90)
        check("Gateway OTLP trace reaches Tempo", True)
        if "--renew-certificate" in __import__("sys").argv:
            certificate = json.loads(
                kube("-n", EG, "get", "certificate", "hapbang-tls", "-o", "json")
            )
            revision = certificate["status"]["revision"]
            try:
                kube(
                    "-n",
                    EG,
                    "patch",
                    "certificate",
                    "hapbang-tls",
                    "--type=merge",
                    "-p",
                    json.dumps({"spec": {"renewBefore": "2159h59m"}}),
                )

                def renewed():
                    latest = json.loads(
                        kube(
                            "-n", EG, "get", "certificate", "hapbang-tls", "-o", "json"
                        )
                    )
                    assert latest["status"]["revision"] > revision

                retry(renewed, seconds=90)
            finally:
                kube(
                    "-n",
                    EG,
                    "patch",
                    "certificate",
                    "hapbang-tls",
                    "--type=merge",
                    "-p",
                    json.dumps({"spec": {"renewBefore": None}}),
                )
            secret = json.loads(
                kube("-n", EG, "get", "secret", "hapbang-tls", "-o", "json")
            )
            cert.write_bytes(base64.b64decode(secret["data"]["tls.crt"]))
            context = ssl.create_default_context(cafile=str(cert))
            retry(ready)
            check("certificate renewal and Envoy TLS reload", True)
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
        print("Gateway integration checks completed", flush=True)
except Exception:
    for kind in (
        "httproute",
        "securitypolicy",
        "envoyextensionpolicy",
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
        "pod,service,configmap,httproute,securitypolicy,backendtrafficpolicy,envoyextensionpolicy,networkpolicy",
        "-l",
        LABEL + "=true",
        "--ignore-not-found=true",
        "--wait=false",
    )
