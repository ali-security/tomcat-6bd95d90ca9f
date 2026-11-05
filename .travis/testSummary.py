#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Sealed-build helper: antTest.sh sends Ant's output to ant-test.log (Travis log
# size limit), so the Travis log itself would carry no test results. This prints
# the results of the plain-formatter reports (output/build/logs/TEST-*.txt):
# one line per suite, every failure, every skipped test with its reason (the
# skip message, or for a bare @Ignore / generic Assume the reason found in the
# test source), and per-connector totals last.
import glob
import os
import re
import sys

LOG_DIR = sys.argv[1] if len(sys.argv) > 1 else "output/build/logs"
SRC_DIR = "test"
GENERIC = re.compile(r"^(got: .*expected: .*|AssumptionViolatedException.*|)$")


def read_source(cls):
    path = os.path.join(SRC_DIR, cls.split("$")[0].replace(".", "/") + ".java")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return None


def comment_before(lines, idx):
    """Comment text immediately above lines[idx] (skipping annotations)."""
    out = []
    i = idx - 1
    while i >= 0:
        s = lines[i].strip()
        if s.startswith("@") or s == "":
            if out:
                break
            i -= 1
            continue
        if s.startswith("//") or s.startswith("*") or s.startswith("/*") or s.endswith("*/"):
            out.insert(0, s.lstrip("/* ").rstrip("*/ ").strip())
            i -= 1
            continue
        break
    return " ".join(x for x in out if x)


def ignore_reason(lines, start, end):
    for j in range(start, end):
        m = re.search(r"@Ignore(\((.*)\))?", lines[j])
        if m:
            val = (m.group(2) or "").strip().strip('"')
            if val:
                return "@Ignore(\"%s\")" % val
            if "//" in lines[j]:
                return "@Ignore, source comment: " + lines[j].split("//", 1)[1].strip()
            c = comment_before(lines, j)
            if c:
                return "@Ignore, source comment: " + c
            return "bare @Ignore in upstream source, upstream gives no comment"
    return None


def source_reason(cls, method):
    lines = read_source(cls)
    if lines is None:
        return None
    midx = None
    if method:
        pat = re.compile(r"\bvoid\s+%s\s*\(" % re.escape(method))
        for i, line in enumerate(lines):
            if pat.search(line):
                midx = i
                break
    if midx is not None:
        r = ignore_reason(lines, max(0, midx - 6), midx)
        if r:
            return r
        depth = 0
        started = False
        for j in range(midx, min(len(lines), midx + 200)):
            s = lines[j]
            if "Assume." in s:
                return "assumption in source: " + s.strip()
            depth += s.count("{") - s.count("}")
            if "{" in s:
                started = True
            if started and depth <= 0:
                break
    # class-level @Ignore
    for i, line in enumerate(lines):
        if re.match(r"\s*public\s+(abstract\s+)?class\s", line):
            r = ignore_reason(lines, max(0, i - 6), i)
            if r:
                return "class-level " + r
            break
    # assumptions in @Before / @BeforeClass / parameterised setup
    for line in lines:
        if "Assume." in line:
            return "assumption in source: " + line.strip()
    return None


def parse(path):
    suite = None
    counts = None
    cases = []  # (name, kind, message)
    in_output = False
    current = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("------------- Standard"):
                in_output = True
                continue
            if line.startswith("------------- ----"):
                in_output = False
                continue
            if in_output:
                continue
            if line.startswith("Testsuite: ") and suite is None:
                suite = line[len("Testsuite: "):].strip()
                continue
            m = re.match(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", line)
            if m and counts is None:
                counts = tuple(int(x) for x in m.groups())
                continue
            m = re.match(r"Testcase: (.+?) took ", line)
            if m:
                current = m.group(1)
                continue
            m = re.match(r"\s+SKIPPED(: ?(.*))?$", line)
            if m:
                cases.append((current, "SKIPPED", (m.group(2) or "").strip()))
                continue
            m = re.match(r"\s+(FAILED|Caused an ERROR)\s*$", line)
            if m:
                cases.append((current, m.group(1), ""))
    return suite, counts, cases


def main():
    totals = {}
    skipped = []
    failed = []
    files = sorted(glob.glob(os.path.join(LOG_DIR, "TEST-*.txt")))
    print("==== Test results from %d report files in %s ====" % (len(files), LOG_DIR))
    for path in files:
        base = os.path.basename(path)[len("TEST-"):-len(".txt")]
        conn = base.rsplit(".", 1)[1] if "." in base else "?"
        suite, counts, cases = parse(path)
        if suite is None:
            suite = base.rsplit(".", 1)[0]
        if counts is None:
            print("SUITE [%s] %s: NO RESULTS (report incomplete)" % (conn, suite))
            failed.append("[%s] %s: report incomplete" % (conn, suite))
            continue
        run, fail, err, skip = counts
        t = totals.setdefault(conn, [0, 0, 0, 0, 0])
        t[0] += 1
        t[1] += run
        t[2] += fail
        t[3] += err
        t[4] += skip
        print("SUITE [%s] %s: run=%d passed=%d failures=%d errors=%d skipped=%d"
              % (conn, suite, run, run - fail - err - skip, fail, err, skip))
        for name, kind, msg in cases:
            if kind == "SKIPPED":
                reason = msg
                if GENERIC.match(msg or ""):
                    method = name if name and name != suite and "." not in name else None
                    src = source_reason(suite, method)
                    if src:
                        reason = (msg + " -- " if msg else "") + src
                    elif not msg:
                        reason = "no message and no reason found in source"
                skipped.append("[%s] %s.%s: %s" % (conn, suite, name, reason))
            else:
                failed.append("[%s] %s.%s: %s" % (conn, suite, name, kind))
    print()
    if failed:
        print("FAILED / ERRORED TESTS (%d):" % len(failed))
        for x in failed:
            print("  " + x)
        print()
    print("ALL SKIPPED TESTS (%d), each with its reason:" % len(skipped))
    for x in skipped:
        print("  " + x)
    print()
    for conn in sorted(totals):
        s, run, fail, err, skip = totals[conn]
        print("TOTAL [%s] suites=%d tests=%d passed=%d failures=%d errors=%d skipped=%d"
              % (conn, s, run, run - fail - err - skip, fail, err, skip))
    print("SEAL-SUMMARY-COMPLETE")


if __name__ == "__main__":
    main()
