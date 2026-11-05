#!/bin/sh
set -e
LIB=/var/minis/shared/work/jvm-libs
CP="$LIB/kotlin-stdlib-2.1.0.jar:$LIB/json-20231013.jar"
rm -rf out && mkdir -p out
nice -n 10 kotlinc -no-stdlib -cp "$CP" -d out src stubs 2>&1 | grep -v "^warning:" | head -40
