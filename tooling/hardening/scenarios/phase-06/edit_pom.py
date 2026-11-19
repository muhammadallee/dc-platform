"""Adds the parked REST-client starter to a generated pom.xml (Spring-native plan v2 §2.4).

The REST client is parked during iteration 1: built and tested, but not in platform-bom and not offered by the
archetype. Its consumer run therefore generates a service with no features and adds the starter here, with the
platform version spelled out because the BOM does not manage it. This is a harness step for a parked module, not a
consumer workaround.

    python -I edit_pom.py <pom.xml> <platform-version>
"""
import sys

path, version = sys.argv[1], sys.argv[2]
pom = open(path, encoding='utf-8').read()
assert '<artifactId>platform-starter-restclient</artifactId>' not in pom, 'the archetype must not offer the parked starter'
added = ('    <dependency>\n      <groupId>ae.gov.dubaicustoms.platform</groupId>\n'
         '      <artifactId>platform-starter-restclient</artifactId>\n'
         '      <version>%s</version>\n    </dependency>\n' % version)
head, tail = pom.rsplit('</dependencies>', 1)
open(path, 'w', encoding='utf-8').write(head.rstrip() + '\n' + added + '  </dependencies>' + tail)
