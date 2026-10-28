import unittest

from affected_projects import select_projects


GRAPH = [
    {"id": ":member-app", "directory": "apps/member-app", "dependencies": [":redis", ":shared", ":test-support"]},
    {"id": ":billing-app", "directory": "apps/billing-app", "dependencies": [":kafka", ":shared", ":test-support"]},
    {"id": ":redis", "directory": "common/redis", "dependencies": []},
    {"id": ":kafka", "directory": "common/kafka", "dependencies": [":shared"]},
    {"id": ":shared", "directory": "common/shared", "dependencies": []},
    {"id": ":test-support", "directory": "common/test-support", "dependencies": [":shared"]},
]


class AffectedProjectsTest(unittest.TestCase):
    def test_app_change_does_not_select_other_apps(self):
        self.assertEqual(select_projects(GRAPH, ["apps/member-app/src/main/App.java"]),
                         {"apps": ["member-app"], "checks": [":member-app:check"]})

    def test_common_change_selects_only_consumers(self):
        self.assertEqual(select_projects(GRAPH, ["common/redis/src/main/RedisConfig.java"]),
                         {"apps": ["member-app"], "checks": [":member-app:check", ":redis:check"]})

    def test_transitive_dependencies_and_test_support_are_included(self):
        result = select_projects(GRAPH, ["common/shared/src/main/Shared.java"])
        self.assertEqual(result["apps"], ["billing-app", "member-app"])
        self.assertIn(":kafka:check", result["checks"])
        self.assertIn(":test-support:check", result["checks"])

    def test_root_build_change_checks_all_projects(self):
        self.assertEqual(len(select_projects(GRAPH, ["gradle/libs.versions.toml"])["checks"]), len(GRAPH))

    def test_deleted_or_unknown_module_is_conservative(self):
        self.assertEqual(select_projects(GRAPH, ["common/removed-module/build.gradle.kts"])["apps"],
                         ["billing-app", "member-app"])

    def test_docs_change_still_produces_valid_empty_matrix(self):
        self.assertEqual(select_projects(GRAPH, ["docs/architecture/app-modules.md"]),
                         {"apps": [], "checks": []})

    def test_unknown_base_checks_everything(self):
        self.assertEqual(len(select_projects(GRAPH, [], force_all=True)["checks"]), len(GRAPH))

    def test_directory_prefix_does_not_match_other_module(self):
        self.assertEqual(select_projects(GRAPH, ["apps/member-app-extra/src/X.java"])["apps"],
                         ["billing-app", "member-app"])

    def test_invalid_project_names_are_rejected(self):
        with self.assertRaises(ValueError):
            select_projects([{"id": ":evil;command", "directory": "apps/evil", "dependencies": []}], [])


if __name__ == "__main__":
    unittest.main()
