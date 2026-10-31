"""Reverse-dependency map of in-scope platform modules, from the POMs (no Maven invocation)."""
import collections
import glob
import os
import sys
import xml.etree.ElementTree as ET

NS = '{http://maven.apache.org/POM/4.0.0}'
GROUP = 'ae.gov.dubaicustoms.platform'
root = sys.argv[1]
os.chdir(root)

deps = {}
for path in glob.glob('**/pom.xml', recursive=True):
    norm = path.replace(os.sep, '/')
    if any(part in norm for part in ('/target/', 'scratch/', 'tmpviol/', '/src/')):
        continue
    project = ET.parse(path).getroot()
    artifact = project.find(NS + 'artifactId').text
    found = set()
    for section in (project.find(NS + 'dependencies'),):
        if section is None:
            continue
        for dep in section.findall(NS + 'dependency'):
            group = dep.find(NS + 'groupId')
            if group is not None and group.text == GROUP:
                found.add(dep.find(NS + 'artifactId').text)
    deps[artifact] = found

reverse = collections.defaultdict(set)
for artifact, found in deps.items():
    for dep in found:
        reverse[dep].add(artifact)


def closure(artifact):
    seen, stack = set(), [artifact]
    while stack:
        for nxt in reverse.get(stack.pop(), ()):
            if nxt not in seen:
                seen.add(nxt)
                stack.append(nxt)
    return seen


IN_SCOPE = [
    'platform-core-api', 'platform-core-autoconfigure', 'platform-errors-api', 'platform-errors-autoconfigure',
    'platform-logging-api', 'platform-logging-autoconfigure', 'platform-observability-autoconfigure',
    'platform-data-api', 'platform-data-jpa-autoconfigure', 'platform-events-api', 'platform-events-autoconfigure',
    'platform-messaging-api', 'platform-messaging-spi', 'platform-messaging-autoconfigure',
    'platform-messaging-rabbit', 'platform-restclient-api', 'platform-restclient-autoconfigure',
    'platform-resilience-api', 'platform-resilience-autoconfigure', 'platform-security-api',
    'platform-security-autoconfigure', 'platform-security-authz-api', 'platform-security-authz-spi',
    'platform-security-authz-autoconfigure',
]
OUT_OF_SCOPE = ('kafka', 'audit', 'idempotency', 'cache', 'redis', 'locking', 'ratelimit', 'storage', 'files',
                'flags', 'openapi', 'scheduling', 'validation', 'tck')

print('| Module | Direct dependents | Transitive | Out-of-scope dependents (must still compile, plan 3.9) |')
print('|---|---|---|---|')
for module in IN_SCOPE:
    direct = sorted(reverse.get(module, ()))
    transitive = closure(module)
    oos = sorted(x for x in transitive if any(k in x for k in OUT_OF_SCOPE))
    shown = ', '.join(direct[:6]) + (f' ... (+{len(direct) - 6})' if len(direct) > 6 else '')
    print(f"| `{module}` | {shown or '-'} | {len(transitive)} | {', '.join(oos) or '-'} |")
