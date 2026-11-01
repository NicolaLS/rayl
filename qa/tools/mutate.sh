#!/usr/bin/env bash
# Manual mutation check (qa/releases/1.1/test-audit.md, criterion C1).
#
# Applies one literal text replacement to a production file, runs a Gradle test task, reports
# which tests failed, and always restores the file. A mutation that no test catches ("SURVIVED")
# means the tests don't check that behaviour.
#
# Usage: qa/tools/mutate.sh <file> <exact text> <replacement> <gradle test task>
# The exact text may span lines and must occur exactly once in the file.
# Example:
#   qa/tools/mutate.sh feature/payment-flow/src/commonMain/.../PaymentOperation.kt \
#     'if (job != null) return' '' :feature:payment-flow:testAndroidHostTest
set -euo pipefail

file=$1 original=$2 replacement=$3 task=$4

if ! git diff --quiet -- "$file"; then
  echo "refusing: $file has uncommitted changes" >&2
  exit 2
fi

ORIGINAL="$original" REPLACEMENT="$replacement" python3 -I - "$file" <<'EOF'
import os, sys
path = sys.argv[1]
text = open(path).read()
count = text.count(os.environ["ORIGINAL"])
if count != 1:
    sys.exit(f"refusing: expected exactly one match in {path}, found {count}")
open(path, "w").write(text.replace(os.environ["ORIGINAL"], os.environ["REPLACEMENT"]))
EOF
trap 'git checkout -- "$file"' EXIT

module_dir=$(echo "$file" | sed -E 's#/src/.*##')
started=$(date +%s)
if ./gradlew "$task" --quiet >/dev/null 2>&1; then
  echo "SURVIVED: no test failed"
  exit 0
fi

python3 -I - "$module_dir/build/test-results" "$started" <<'EOF'
import glob, os, sys, xml.etree.ElementTree as ET
results, started = sys.argv[1], int(sys.argv[2])
fresh = [p for p in glob.glob(f"{results}/*/TEST-*.xml") if os.path.getmtime(p) >= started]
failed = [
    f"{case.get('classname').rsplit('.', 1)[-1]}.{case.get('name')}"
    for path in fresh
    for case in ET.parse(path).getroot().iter("testcase")
    if case.find("failure") is not None or case.find("error") is not None
]
if failed:
    print("KILLED by:")
    for name in sorted(failed):
        print(f"  {name}")
else:
    print("NOT A TEST FAILURE: the build failed before tests reported (e.g. compile error)")
EOF
