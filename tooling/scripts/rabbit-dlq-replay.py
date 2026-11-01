#!/usr/bin/env python3
"""Inspects a platform dead-letter queue, and replays it into its main queue, through RabbitMQ's management API.

    RABBIT_MGMT_URL=http://host:15672 RABBIT_USER=ops RABBIT_PASSWORD=... [RABBIT_VHOST=/] \\
      python tooling/scripts/rabbit-dlq-replay.py inspect <main-queue> [count]
      python tooling/scripts/rabbit-dlq-replay.py replay  <main-queue>

<main-queue> is the queue the service consumes, such as dc.orders.placed.billing; its DLQ is <main-queue>.dlq.

inspect prints each message's headers, not its payload, and leaves the messages in place.

replay starts a dynamic shovel that moves what is in the DLQ now, then deletes itself. It is at-least-once: a message can arrive
twice, under the same eventId, and consumers deduplicate. Fix the cause first, or the messages come straight back. Do not delete
the main queue during a replay: the shovel would publish into nothing.

Needs the rabbitmq_shovel and rabbitmq_shovel_management plugins, and a management user allowed to set parameters. Credentials come
from the environment, so they never appear in a process list.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def api(method, path, body=None):
    url = os.environ["RABBIT_MGMT_URL"].rstrip("/") + "/api/" + path
    credentials = f"{os.environ['RABBIT_USER']}:{os.environ['RABBIT_PASSWORD']}".encode()
    request = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(), headers={
        "Authorization": "Basic " + base64.b64encode(credentials).decode(),
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path}: HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")
    return json.loads(content) if content else None


def quoted(value):
    return urllib.parse.quote(value, safe="")


def main(argv):
    if len(argv) < 3 or argv[1] not in ("inspect", "replay"):
        sys.exit(__doc__)
    action, queue = argv[1], argv[2]
    vhost = os.environ.get("RABBIT_VHOST", "/")
    dead = f"{queue}.dlq"

    if action == "inspect":
        count = int(argv[3]) if len(argv) > 3 else 20
        messages = api("POST", f"queues/{quoted(vhost)}/{quoted(dead)}/get",
                       {"count": count, "ackmode": "ack_requeue_true", "encoding": "base64", "truncate": 1})
        for message in messages:
            print(json.dumps(message["properties"].get("headers", {}), sort_keys=True))
        return

    # Fails here, with nothing moved, if either queue is missing.
    api("GET", f"queues/{quoted(vhost)}/{quoted(queue)}")
    api("GET", f"queues/{quoted(vhost)}/{quoted(dead)}")
    uri = "amqp:///" + quoted(vhost)
    name = f"replay-{queue}-{int(time.time())}"
    api("PUT", f"parameters/shovel/{quoted(vhost)}/{quoted(name)}", {"value": {
        "src-protocol": "amqp091", "src-uri": uri, "src-queue": dead, "src-delete-after": "queue-length",
        "dest-protocol": "amqp091", "dest-uri": uri, "dest-queue": queue, "ack-mode": "on-confirm",
    }})
    print(f"started shovel {name}: {dead} -> {queue}")


if __name__ == "__main__":
    main(sys.argv)
