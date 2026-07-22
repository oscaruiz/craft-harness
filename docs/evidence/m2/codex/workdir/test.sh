#!/usr/bin/env bash
set -euo pipefail
[[ "$(bash ./sut.sh)" == "42" ]]
