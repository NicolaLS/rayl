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

# Results are written by the module that owns the test task, which may differ from the mutated
# file's module (e.g. a settlement test exercising another module's code).
module_dir=$(echo "$task" | sed -E 's#^:##; s#:[^:]+$##; s#:#/#g')
results_dir="$module_dir/build/test-results"
# Old result files must never be mistaken for this run's (a mutant that doesn't compile runs no
# tests at all).
rm -rf "$results_dir"
log=$(mktemp)
if ./gradlew "$task" --rerun --quiet >"$log" 2>&1; then
  echo "SURVIVED: no test failed"
  rm -f "$log"
  exit 0
fi
if grep -qE '^e: ' "$log"; then
  echo "INVALID: the mutant does not compile"
  grep -E '^e: ' "$log" | head -3 | sed 's/^/  /'
  rm -f "$log"
  exit 0
fi
rm -f "$log"

python3 -I - "$results_dir" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
failed = [
    f"{case.get('classname').rsplit('.', 1)[-1]}.{case.get('name')}"
    for path in glob.glob(f"{sys.argv[1]}/*/TEST-*.xml")
    for case in ET.parse(path).getroot().iter("testcase")
    if case.find("failure") is not None or case.find("error") is not None
]
if failed:
    print("KILLED by:")
    for name in sorted(failed):
        print(f"  {name}")
else:
    print("NOT A TEST FAILURE: the build failed for another reason")
PY
