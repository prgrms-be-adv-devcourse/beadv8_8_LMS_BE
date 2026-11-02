"""Exercise production rendering with the repository's real Kustomize overlays."""

import os
import pathlib
import subprocess
import sys
import unittest

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[3]


class ProductionRenderTest(unittest.TestCase):
    def render(self, *args, email="ops@example.org"):
        env = dict(os.environ, GATEWAY_HOST="example.org", ACME_EMAIL=email)
        result = subprocess.run(
            [sys.executable, "k8s/gateway/tools/render-prod.py", *args],
            cwd=ROOT, env=env, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return list(yaml.safe_load_all(result.stdout))

    def test_gateway_render_excludes_apps_and_sets_host(self):
        docs = self.render()
        self.assertFalse(any(d["kind"] == "Deployment" for d in docs))
        self.assertFalse(any(
            d["kind"] == "HTTPRoute" and d["metadata"].get("namespace") == "hapbang-prod"
            for d in docs
        ))
        # 앱 JWT 설정(issuer)은 앱 prod overlay가 원본이다(#500). 렌더러는 앱 SecurityPolicy를 내지 않는다.
        self.assertFalse(any("jwt" in d.get("spec", {}) for d in docs))
        gateway = next(d for d in docs if d["kind"] == "Gateway")
        self.assertTrue(all(l["hostname"] == "example.org" for l in gateway["spec"]["listeners"]))
        certificate = next(d for d in docs if d["kind"] == "Certificate")
        self.assertEqual(certificate["spec"]["dnsNames"], ["example.org"])

    def test_gateway_render_preserves_basic_auth(self):
        docs = self.render()
        basic = next(
            d for d in docs
            if d["kind"] == "SecurityPolicy" and d["metadata"]["name"] == "kafka-ui"
        )
        self.assertEqual(basic["spec"]["basicAuth"]["users"]["name"], "kafka-ui-basic-auth")

    def test_explicit_contactless_acme_omits_email(self):
        docs = self.render("--without-acme-email", email="")
        issuers = [d for d in docs if d["kind"] == "ClusterIssuer"]
        self.assertEqual(len(issuers), 2)
        self.assertTrue(all("email" not in d["spec"]["acme"] for d in issuers))


if __name__ == "__main__":
    unittest.main()
