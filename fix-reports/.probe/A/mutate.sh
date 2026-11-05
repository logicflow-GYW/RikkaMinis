#!/bin/sh
# $1 = python mutation snippet file applied to the production file copy
set -e
REPO=/var/minis/shared/work/meta-audit-1105b/openai-responses
F=$REPO/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt
cp /tmp/OpenAIProvider.fixed.kt "$F"
python3 "$1"
cd /tmp/probeA2 && python3 build.py >/dev/null && ./run.sh
java -cp "out:/var/minis/shared/work/jvm-libs/kotlin-stdlib-2.1.0.jar:/var/minis/shared/work/jvm-libs/json-20231013.jar" com.rikkaminis.app.provider.openai.ProbeAKt 2>&1 | tail -12
