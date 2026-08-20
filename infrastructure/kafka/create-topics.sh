#!/usr/bin/env bash
set -euo pipefail

brokers="${KAFKA_BROKERS:-redpanda:9092}"
topic="market.order.events.v1"

if rpk topic describe "${topic}" --brokers "${brokers}" >/dev/null 2>&1; then
  echo "Topic ${topic} already exists"
else
  rpk topic create "${topic}" \
    --brokers "${brokers}" \
    --partitions 3 \
    --replicas 1 \
    --topic-config cleanup.policy=delete \
    --topic-config retention.ms=604800000
fi

rpk topic describe "${topic}" --brokers "${brokers}"
