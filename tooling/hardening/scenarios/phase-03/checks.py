"""Checks for the hardening phase-03 consumer harness (data access on Oracle).

Each check prints one JSON summary line (the assertion text the scenario records) and exits 0 on pass,
1 on fail. Run with ``python -I``; standard library only.

  burst <dir> <ok> <fail-max-s> <ok-max-s>   parallel results: <dir>/<i>.result ("status seconds") and
                                             <i>.body; exactly <ok> 200s (-1: any number), every other
                                             response a 503 DC-DATA-0503 within <fail-max-s>, every 200
                                             within <ok-max-s>
  clean-body <body> <code> <token>...        problem body with that code; no token (case-insensitive)
  warn-on-trace <log> <traceId> <code>       exactly one WARN on the trace, naming the code, ORA- in it
  meters <scrape> <forbidden>...             Hikari + repository meters present; no forbidden text in a
                                             label value
"""
import json
import re
import sys
from pathlib import Path


def emit(ok, **summary):
    print(json.dumps(dict(ok=ok, **summary), sort_keys=True))
    sys.exit(0 if ok else 1)


def burst(directory, expected_ok, fail_max, ok_max):
    expected_ok, fail_max, ok_max = int(expected_ok), float(fail_max), float(ok_max)
    results, problems = [], []
    for path in sorted(Path(directory).glob('*.result')):
        status, seconds = path.read_text(encoding='utf-8').split()
        body_path = path.with_suffix('.body')
        body = body_path.read_text(encoding='utf-8', errors='replace') if body_path.exists() else ''
        results.append((path.stem, status, float(seconds), body))
    counts = {}
    for _, status, _, _ in results:
        counts[status] = counts.get(status, 0) + 1
    for name, status, seconds, body in results:
        if status == '200':
            if seconds > ok_max:
                problems.append(f'{name}: 200 after {seconds:.1f}s > {ok_max}s')
        elif status == '503':
            try:
                code = json.loads(body).get('code')
            except ValueError:
                code = None
            if code != 'DC-DATA-0503':
                problems.append(f'{name}: 503 with code {code!r}')
            if seconds > fail_max:
                problems.append(f'{name}: 503 after {seconds:.1f}s > {fail_max}s')
        else:
            problems.append(f'{name}: status {status}')
    if expected_ok >= 0 and counts.get('200', 0) != expected_ok:
        problems.append(f"{counts.get('200', 0)} x 200, expected {expected_ok}")
    timing = {s: round(max(t for _, st, t, _ in results if st == s), 1) for s in counts}
    emit(not problems and bool(results), requests=len(results), counts=counts, max_seconds=timing,
         problems=problems[:10])


def clean_body(body_file, code, *tokens):
    text = Path(body_file).read_text(encoding='utf-8', errors='replace')
    try:
        body = json.loads(text)
    except ValueError:
        emit(False, reason='not JSON', body=text[:200])
    lower = text.lower()
    found = [t for t in tokens if t.lower() in lower]
    emit(body.get('code') == code and not found, code=body.get('code'), leaked=found,
         detail=body.get('detail'))


def warn_on_trace(log_file, trace_id, code):
    warns = []
    for line in Path(log_file).read_text(encoding='utf-8', errors='replace').splitlines():
        if not line.startswith('{'):
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get('traceId') == trace_id and event.get('level') == 'WARN':
            warns.append(event)
    detail = json.dumps(warns[0]) if warns else ''
    emit(len(warns) == 1 and code in detail and 'ORA-' in detail, warns=len(warns),
         message=warns[0].get('message') if warns else None, ora_in_detail='ORA-' in detail)


def meters(scrape_file, *forbidden):
    text = Path(scrape_file).read_text(encoding='utf-8', errors='replace')
    required = ['hikaricp_connections_active', 'hikaricp_connections_pending', 'hikaricp_connections_timeout_total',
                'spring_data_repository_invocations_seconds']
    missing = [m for m in required if m not in text]
    values = set(re.findall(r'="([^"]*)"', text))
    leaked = sorted({v for v in values for f in forbidden if f.lower() in v.lower()})
    sql_like = sorted(v for v in values if re.search(r'\b(select|insert|update|delete)\b', v, re.I))
    emit(not missing and not leaked and not sql_like, missing=missing, leaked=leaked[:5], sql_like=sql_like[:5],
         label_values=len(values))


CHECKS = {'burst': burst, 'clean-body': clean_body, 'warn-on-trace': warn_on_trace, 'meters': meters}

if __name__ == '__main__':
    CHECKS[sys.argv[1]](*sys.argv[2:])
