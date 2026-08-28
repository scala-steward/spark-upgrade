import os
import re
import sys

original_build = sys.stdin.read()

build_with_plugin = original_build

# No default on purpose. run_demo-gradle.sh is the only caller and it exports
# SCALAFIX_RULES_VERSION, so a default here would just be a fourth place for the
# version to drift -- which is exactly how the two demos ended up exercising
# different releases of the rules. Fail loudly instead of silently rewriting the
# build to point at some stale version.
version = os.getenv("SCALAFIX_RULES_VERSION", "")
if not version:
    raise SystemExit(
        "SCALAFIX_RULES_VERSION is unset or empty; refusing to guess a version. "
        "Run this via run_demo-gradle.sh, which exports it."
    )

if "scalafix" not in build_with_plugin:
    build_with_plugin = re.sub(
        r"plugins\s*{",
        "plugins {\n    id 'io.github.cosmicsilence.scalafix' version '0.1.14'\n",
        build_with_plugin
    )

rules_dep = (
    "dependencies {\n"
    "    scalafix group: 'com.holdenkarau',"
    " name: 'spark-scalafix-rules-2.4.8_2.12',"
    " version: '" + version + "'\n"
)

build_with_plugin_and_rules = re.sub(
    r"dependencies\s*{",
    rules_dep,
    build_with_plugin)

print(build_with_plugin_and_rules)
