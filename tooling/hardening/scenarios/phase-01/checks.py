"""Checks for the phase-01 consumer scenario that are awkward in shell: concurrency and log scans.

    python -I checks.py concurrent BASE_URL N ROUNDS     # N parallel /probe/context calls per round
    python -I checks.py concurrent-errors BASE_URL N ROUNDS  # N parallel failing calls: own traceId per body
    python -I checks.py logs APP_LOG TRACE_ID [FORBIDDEN...]

Exit code 0 = pass, 1 = fail; a one-line JSON summary goes to stdout either way.
"""
import concurrent.futures
import json
import secrets
import sys
import urllib.error
import urllib.request


def get_context(base, trace_id):
    request = urllib.request.Request(base + '/probe/context',
                                     headers={'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01'})
    with urllib.request.urlopen(request, timeout=30) as response:
        headers = {k.lower(): v for k, v in response.getheaders()}
        return json.load(response), headers


def concurrent_check(base, n, rounds):
    bleed = []
    for round_no in range(rounds):
        ids = [secrets.token_hex(16) for _ in range(n)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
            replies = list(pool.map(lambda trace_id: get_context(base, trace_id), ids))
        for sent, (body, headers) in zip(ids, replies):
            seen = {'context': body.get('context'), 'mdc': body.get('mdcTraceId'),
                    'async': body.get('async', {}).get('context'), 'asyncMdc': body.get('async', {}).get('mdcTraceId')}
            if any(value != sent for value in seen.values()) or 'x-correlation-id' in headers \
                    or 'mdcCorrelationId' in body:
                bleed.append({'round': round_no, 'sent': sent, 'seen': seen})
    print(json.dumps({'check': 'concurrent', 'requests': n * rounds, 'rounds': rounds, 'bleed': len(bleed),
                      'examples': bleed[:3]}))
    return not bleed


def get_error(base, trace_id):
    request = urllib.request.Request(base + '/probe/boom',
                                     headers={'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01'})
    try:
        urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)
    return 200, {}


def concurrent_errors_check(base, n, rounds):
    wrong = []
    for round_no in range(rounds):
        ids = [secrets.token_hex(16) for _ in range(n)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
            replies = list(pool.map(lambda trace_id: get_error(base, trace_id), ids))
        for sent, (status, body) in zip(ids, replies):
            if status != 500 or body.get('traceId') != sent or body.get('code') != 'DC-CORE-0500':
                wrong.append({'round': round_no, 'sent': sent, 'status': status, 'traceId': body.get('traceId')})
    print(json.dumps({'check': 'concurrent-errors', 'requests': n * rounds, 'rounds': rounds, 'wrong': len(wrong),
                      'examples': wrong[:3]}))
    return not wrong


def logs_check(path, trace_id, forbidden):
    events, non_json, problems = [], 0, []
    with open(path, encoding='utf-8', errors='replace') as log:
        for line in log:
            line = line.strip()
            if not line.startswith('{'):
                non_json += 1
                continue
            try:
                events.append(json.loads(line))
            except ValueError:
                problems.append('unparseable JSON line')
    with open(path, encoding='utf-8', errors='replace') as log:
        raw = log.read()
    if not any(event.get('traceId') == trace_id for event in events):
        problems.append(f'no event carries traceId {trace_id}')
    if any('correlationId' in event for event in events):
        problems.append('an event carries the removed correlationId field')
    for value in forbidden:
        if value in raw:
            problems.append(f'forbidden value present in log: {value[:40]}')
    print(json.dumps({'check': 'logs', 'events': len(events), 'non_json_lines': non_json, 'problems': problems}))
    return not problems


if __name__ == '__main__':
    if sys.argv[1] == 'concurrent':
        ok = concurrent_check(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    elif sys.argv[1] == 'concurrent-errors':
        ok = concurrent_errors_check(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    elif sys.argv[1] == 'logs':
        ok = logs_check(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        raise SystemExit('unknown check ' + sys.argv[1])
    sys.exit(0 if ok else 1)
