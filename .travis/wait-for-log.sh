#!/bin/bash
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

# Sealed-build helper (after_script, cannot change the build result): Travis
# archives job logs asynchronously, so a log fetched right after the job ends can
# stop before the test summary. Keep the job open until this job's own public
# log.txt contains the end-of-summary marker printed by testSummary.py, capped
# at 900 s, printing a heartbeat on every poll (Travis kills jobs silent >10 min).
URL="https://api.travis-ci.com/v3/job/${TRAVIS_JOB_ID}/log.txt"
MARK='SEAL''-SUMMARY-''COMPLETE'
waited=0
while [ $waited -lt 900 ]; do
    if curl -fsSL "$URL" 2>/dev/null | grep -q "^$MARK"; then
        echo "wait-for-log: test summary archived after ${waited}s"
        exit 0
    fi
    echo "wait-for-log: waiting for log archive... (${waited}s)"
    sleep 20
    waited=$((waited + 20))
done
echo "wait-for-log: gave up after ${waited}s"
exit 0
