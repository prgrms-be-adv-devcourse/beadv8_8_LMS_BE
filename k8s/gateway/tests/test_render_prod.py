"""Exercise production rendering with the repository's real Kustomize overlays."""

import os
import pathlib
import subprocess
import sys
import unittest

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[3]


class ProductionRenderTest(unittest.TestCase):
    def render(self, *args, jwt=True, email="ops@example.org"):
        env = dict(os.environ, GATEWAY_HOST="example.org", ACME_EMAIL=email)
        env["JWT_ISSUER"] = "https://issuer.example.org" if jwt else ""
        env["MEMBER_JWKS_URI"] = (
            "http://member-app.hapbang-prod.svc.cluster.local:8080"
            "/api/v1/members/.well-known/jwks.json" if jwt else ""
        )
        result = subprocess.run(
            [sys.executable, "k8s/gateway/tools/render-prod.py", *args],
            cwd=ROOT, env=env, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return list(yaml.safe_load_all(result.stdout))

    def test_full_render_preserves_basic_auth_and_sets_jwt(self):
        docs = self.render()
        policies = [d for d in docs if d["kind"] == "SecurityPolicy"]
        basic = next(d for d in policies if d["metadata"]["name"] == "kafka-ui")
        self.assertNotIn("jwt", basic["spec"])
        self.assertEqual(basic["spec"]["basicAuth"]["users"]["name"], "kafka-ui-basic-auth")
        jwt_policies = [d for d in policies if "jwt" in d["spec"]]
        self.assertTrue(jwt_policies)
        for policy in jwt_policies:
            for provider in policy["spec"]["jwt"]["providers"]:
                self.assertEqual(provider["issuer"], "https://issuer.example.org")

    def test_gateway_can_be_prepared_without_app_jwt_configuration(self):
        docs = self.render("--gateway-only", jwt=False)
        self.assertFalse(any(d["kind"] == "Deployment" for d in docs))
        self.assertFalse(any(
            d["kind"] == "HTTPRoute" and d["metadata"].get("namespace") == "hapbang-prod"
            for d in docs
        ))
        self.assertFalse(any("jwt" in d.get("spec", {}) for d in docs))
        gateway = next(d for d in docs if d["kind"] == "Gateway")
        self.assertTrue(all(l["hostname"] == "example.org" for l in gateway["spec"]["listeners"]))
        certificate = next(d for d in docs if d["kind"] == "Certificate")
        self.assertEqual(certificate["spec"]["dnsNames"], ["example.org"])

    def test_explicit_contactless_acme_omits_email(self):
        docs = self.render("--gateway-only", "--without-acme-email", jwt=False, email="")
        issuers = [d for d in docs if d["kind"] == "ClusterIssuer"]
        self.assertEqual(len(issuers), 2)
        self.assertTrue(all("email" not in d["spec"]["acme"] for d in issuers))


if __name__ == "__main__":
    unittest.main()
