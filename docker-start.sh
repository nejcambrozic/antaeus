#!/bin/sh

set -x

# Create a new image version with latest code changes.
docker build . --tag pleo-antaeus

# Build the code.
docker run \
  --publish 7000:7000 \
  --rm \
  --interactive \
  --tty \
  --volume pleo-antaeus-build-cache:/root/.gradle \
  -e PROCESS_INVOICE_RETRY_COUNT="3" \
  -e NETWORK_TIMEOUT_ON_ERROR_SECONDS="5000" \
  pleo-antaeus
