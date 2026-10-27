"""Checks for the phase-02 consumer scenario that are awkward in shell: log, scrape and collector scans.

    python -I checks.py json-lines APP_LOG                       # every stdout line is one JSON object
    python -I checks.py line-shape APP_LOG TRACE_ID              # AC1 required fields on a request line
    python -I checks.py one-event APP_LOG MARKER                 # AC2 exactly one event contains MARKER
    python -I checks.py absent APP_LOG VALUE...                  # AC5 none of the values in the raw log
    python -I checks.py present APP_LOG VALUE...                 # AC5 negatives: every value still there
    python -I checks.py mdc APP_LOG TENANT MODE                  # AC3 default|strict
    python -I checks.py correlated BASE_URL N ROUNDS APP_CONTAINER   # AC4 200 x 3, every line on its trace
    python -I checks.py scrape SCRAPE_FILE SERVICE               # AC7 common tags, no ID-like label values
    python -I checks.py uri-series SCRAPE_FILE MAX               # AC8 client uri series bound
    python -I checks.py collector OUT_DIR SERVICE TRACE_ID       # AC11 spans + metrics with service.name
    python -I checks.py latency BASE_URL N                       # AC12 p50/p99/status of N sequential calls

Exit code 0 = pass, 1 = fail; a one-line JSON summary goes to stdout either way.
"""
import concurrent.futures
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

REQUIRED = ('@timestamp', 'level', 'logger', 'message', 'service')
ID_LIKE = re.compile(r'^([0-9a-f]{32}|[0-9a-f]{16}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|eyJ.*)$')


def events(path):
    parsed, non_json, broken = [], [], 0
    with open(path, encoding='utf-8', errors='replace') as log:
        for line in log:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            if not line.startswith('{'):
                non_json.append(line[:200])
                continue
            try:
                parsed.append(json.loads(line))
            except ValueError:
                broken += 1
    return parsed, non_json, broken


def raw(path):
    with open(path, encoding='utf-8', errors='replace') as log:
        return log.read()


def json_lines(path):
    parsed, non_json, broken = events(path)
    # The JVM's own pre-logging output (none expected) would show up here.
    print(json.dumps({'check': 'json-lines', 'events': len(parsed), 'non_json': len(non_json),
                      'unparseable': broken, 'examples': non_json[:3]}))
    return parsed and not non_json and not broken


def line_shape(path, trace_id):
    parsed, _, _ = events(path)
    lines = [e for e in parsed if e.get('traceId') == trace_id]
    problems = []
    if not lines:
        problems.append('no line carries the trace')
    for event in lines:
        missing = [k for k in REQUIRED if k not in event]
        if missing:
            problems.append(f'missing {missing}')
        if not re.fullmatch(r'[0-9a-f]{16}', str(event.get('spanId', ''))):
            problems.append('spanId missing or not 16 hex')
    errors = [e for e in lines if e.get('level') == 'ERROR']
    if errors and not all('stack_trace' in e for e in errors):
        problems.append('an ERROR line has no stack_trace')
    print(json.dumps({'check': 'line-shape', 'lines': len(lines), 'errors': len(errors), 'problems': problems[:5]}))
    return not problems


def one_event(path, marker):
    parsed, _, _ = events(path)
    hits = [e for e in parsed if marker in json.dumps(e)]
    raw_lines = [line for line in raw(path).splitlines() if marker in line]
    ok = len(hits) == 1 and len(raw_lines) == 1
    forged = [e for e in parsed if e.get('message') == 'FORGED']
    print(json.dumps({'check': 'one-event', 'events': len(hits), 'raw_lines': len(raw_lines),
                      'forged_events': len(forged)}))
    return ok and not forged


def absent(path, values):
    text = raw(path)
    found = [v[:6] + '…' for v in values if v and v in text]
    print(json.dumps({'check': 'absent', 'values': len(values), 'found': found, 'masks': text.count('****')}))
    return not found and '****' in text


def present(path, values):
    text = raw(path)
    missing = [v for v in values if v not in text]
    print(json.dumps({'check': 'present', 'values': len(values), 'missing': missing}))
    return not missing


def mdc(path, tenant, mode):
    parsed, _, _ = events(path)
    lines = [e for e in parsed if e.get('message') == f'mdc probe {tenant}']
    problems = []
    if len(lines) != 1:
        problems.append(f'{len(lines)} probe lines')
    for event in lines:
        if event.get('tenant') != tenant:
            problems.append('tenant missing')
        if not re.fullmatch(r'[0-9a-f]{32}', str(event.get('traceId', ''))):
            problems.append('traceId missing')
        if mode == 'default':
            if event.get('apiKey') != '****':
                problems.append(f'apiKey not masked: {str(event.get("apiKey"))[:12]}')
            if event.get('noise') != f'noise-{tenant}':
                problems.append('noise key not passed through')
        else:
            if 'apiKey' in event or 'noise' in event:
                problems.append('unlisted MDC key present in strict mode')
    print(json.dumps({'check': 'mdc', 'mode': mode, 'problems': problems}))
    return not problems


def get(base, path, headers=None, timeout=30):
    request = urllib.request.Request(base + path, headers=headers or {})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            return response.status, body, time.monotonic() - started
    except urllib.error.HTTPError as error:
        return error.code, error.read(), time.monotonic() - started


def correlated(base, n, rounds, container):
    sent_all, wrong = [], []
    for round_no in range(rounds):
        ids = [secrets.token_hex(16) for _ in range(n)]
        sent_all += ids

        def call(trace_id):
            return get(base, '/probe/correlated', {'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01'})
        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
            replies = list(pool.map(call, ids))
        for sent, (status, body, _) in zip(ids, replies):
            if status != 200 or json.loads(body).get('context') != sent:
                wrong.append({'round': round_no, 'sent': sent, 'status': status})
    time.sleep(1)
    log = subprocess.run(['docker', 'logs', container], capture_output=True, text=True, encoding='utf-8',
                         errors='replace').stdout
    seen, mismatched = {}, []
    for line in log.splitlines():
        if not line.startswith('{'):
            continue
        event = json.loads(line)
        message = event.get('message', '')
        match = re.fullmatch(r'correlated(?:-async)? ([0-9a-f]{32})', message)
        if not match:
            continue
        expected = match.group(1)
        seen[expected] = seen.get(expected, 0) + 1
        if event.get('traceId') != expected:
            mismatched.append({'message': message[-12:], 'traceId': str(event.get('traceId'))[-12:]})
    missing = [t for t in sent_all if seen.get(t) != 2]
    print(json.dumps({'check': 'correlated', 'requests': len(sent_all), 'wrong_bodies': len(wrong),
                      'lines_checked': sum(seen.values()), 'mismatched': len(mismatched),
                      'requests_without_two_lines': len(missing), 'examples': mismatched[:3]}))
    return not wrong and not mismatched and not missing


def scrape_series(text):
    for line in text.splitlines():
        if line.startswith('#') or '{' not in line:
            continue
        name, rest = line.split('{', 1)
        labels = dict(re.findall(r'(\w+)="((?:[^"\\]|\\.)*)"', rest.rsplit('}', 1)[0]))
        yield name, labels


def scrape(path, service):
    text = raw(path)
    problems = []
    series = list(scrape_series(text))
    if not series:
        problems.append('empty scrape')
    if not any(l.get('service') == service and 'env' in l and 'platform_version' in l for _, l in series):
        problems.append('common tags missing')
    id_like = sorted({v for _, l in series for v in l.values() if ID_LIKE.match(v)})
    if id_like:
        problems.append(f'{len(id_like)} id-like label values')
    print(json.dumps({'check': 'scrape', 'series': len(series), 'problems': problems,
                      'examples': [v[:8] + '…' for v in id_like[:3]]}))
    return not problems


def uri_series(path, maximum):
    client = {l.get('uri') for n, l in scrape_series(raw(path)) if n.startswith('http_client_requests')}
    echo = {u for u in client if u and u.startswith('/probe/echo/')}
    server = {l.get('uri') for n, l in scrape_series(raw(path)) if n.startswith('http_server_requests')}
    leaked = sorted(u for u in server if u and 'nope-' in u)
    ok = 0 < len(client) <= maximum and not leaked
    print(json.dumps({'check': 'uri-series', 'client_uri_values': len(client), 'echo_uri_values': len(echo),
                      'max': maximum, 'server_unmatched_leaked': leaked[:3]}))
    return ok


def collector(out_dir, service, trace_id):
    spans, metrics, services, outbound = 0, 0, set(), 0
    traces_with_id = 0
    for name in ('traces.json', 'metrics.json'):
        path = os.path.join(out_dir, name)
        if not os.path.exists(path):
            continue
        for line in open(path, encoding='utf-8', errors='replace'):
            try:
                payload = json.loads(line)
            except ValueError:
                continue
            for resource in payload.get('resourceSpans', []) + payload.get('resourceMetrics', []):
                attrs = {a['key']: a['value'].get('stringValue') for a in resource.get('resource', {}).get('attributes', [])}
                services.add(attrs.get('service.name'))
                for scope in resource.get('scopeSpans', []):
                    for span in scope.get('spans', []):
                        spans += 1
                        if span.get('traceId') == trace_id:
                            traces_with_id += 1
                            if span.get('kind') in ('SPAN_KIND_CLIENT', 3):
                                outbound += 1
                for scope in resource.get('scopeMetrics', []):
                    metrics += len(scope.get('metrics', []))
    ok = spans > 0 and metrics > 0 and services == {service} and traces_with_id >= 2 and outbound >= 1
    print(json.dumps({'check': 'collector', 'spans': spans, 'metrics': metrics, 'service_names': sorted(map(str, services)),
                      'spans_on_trace': traces_with_id, 'client_spans_on_trace': outbound}))
    return ok


def latency(base, n):
    timings, statuses = [], {}
    for _ in range(n):
        status, _, seconds = get(base, '/probe/context', {'traceparent': f'00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01'})
        statuses[status] = statuses.get(status, 0) + 1
        timings.append(seconds * 1000)
    timings.sort()
    result = {'check': 'latency', 'n': n, 'statuses': statuses,
              'p50_ms': round(timings[len(timings) // 2], 1), 'p99_ms': round(timings[int(len(timings) * 0.99) - 1], 1),
              'max_ms': round(timings[-1], 1)}
    print(json.dumps(result))
    return statuses == {200: n}


if __name__ == '__main__':
    command, args = sys.argv[1], sys.argv[2:]
    checks = {
        'json-lines': lambda: json_lines(args[0]),
        'line-shape': lambda: line_shape(args[0], args[1]),
        'one-event': lambda: one_event(args[0], args[1]),
        'absent': lambda: absent(args[0], args[1:]),
        'present': lambda: present(args[0], args[1:]),
        'mdc': lambda: mdc(args[0], args[1], args[2]),
        'correlated': lambda: correlated(args[0], int(args[1]), int(args[2]), args[3]),
        'scrape': lambda: scrape(args[0], args[1]),
        'uri-series': lambda: uri_series(args[0], int(args[1])),
        'collector': lambda: collector(args[0], args[1], args[2]),
        'latency': lambda: latency(args[0], int(args[1])),
    }
    if command not in checks:
        raise SystemExit('unknown check ' + command)
    sys.exit(0 if checks[command]() else 1)
