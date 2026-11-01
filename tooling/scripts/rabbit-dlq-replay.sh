#!/usr/bin/env bash
# Inspects a platform dead-letter queue, and replays it into its main queue, through RabbitMQ's management API.
#
#   RABBIT_MGMT_URL=http://host:15672 RABBIT_USER=ops RABBIT_PASSWORD=... \
#     tooling/scripts/rabbit-dlq-replay.sh inspect <main-queue> [count]
#     tooling/scripts/rabbit-dlq-replay.sh replay  <main-queue>
#
# <main-queue> is the queue the service consumes, such as dc.orders.placed.billing; its DLQ is <main-queue>.dlq.
#
# inspect prints each message's headers, not its payload, and leaves the messages in place.
# replay starts a dynamic shovel that moves what is in the DLQ now, then deletes itself. It is at-least-once: a message can
# arrive twice, under the same eventId, and consumers deduplicate. Fix the cause first, or the messages come straight back.
# Needs the rabbitmq_shovel and rabbitmq_shovel_management plugins, and a management user allowed to set parameters.
set -euo pipefail

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ $# -ge 2 ] || usage
: "${RABBIT_MGMT_URL:?set RABBIT_MGMT_URL}" "${RABBIT_USER:?set RABBIT_USER}" "${RABBIT_PASSWORD:?set RABBIT_PASSWORD}"
ACTION=$1 QUEUE=$2 COUNT=${3:-20} VHOST=${RABBIT_VHOST:-%2f}

# The credentials go through stdin, so they never appear in a process list.
api() { # method path [json]
  printf 'user = "%s:%s"\n' "$RABBIT_USER" "$RABBIT_PASSWORD" | curl -sS --fail-with-body --max-time 30 --config - \
      -X "$1" "$RABBIT_MGMT_URL/api/$2" -H 'Content-Type: application/json' ${3:+--data "$3"}
}

case "$ACTION" in
  inspect)
    api POST "queues/$VHOST/$QUEUE.dlq/get" \
        "{\"count\":$COUNT,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"base64\",\"truncate\":1}" \
      | python -I -c "
import json, sys
for message in json.load(sys.stdin):
    print(json.dumps(message['properties'].get('headers', {}), sort_keys=True))
"
    ;;
  replay)
    NAME="replay-$QUEUE-$(date +%s)"
    api PUT "parameters/shovel/$VHOST/$NAME" "{\"value\":{\"src-protocol\":\"amqp091\",\"src-uri\":\"amqp://\",\
\"src-queue\":\"$QUEUE.dlq\",\"src-delete-after\":\"queue-length\",\"dest-protocol\":\"amqp091\",\"dest-uri\":\"amqp://\",\
\"dest-queue\":\"$QUEUE\",\"ack-mode\":\"on-confirm\"}}" > /dev/null
    echo "started shovel $NAME: $QUEUE.dlq -> $QUEUE"
    ;;
  *) usage ;;
esac
