"""Adopts the outbox in a generated pom.xml as docs/modules/outbox.md says: RabbitMQ for the sample's in-memory transport,
plus the outbox starter. (The events starter and its relay scenario went with the events module: Spring-native
Phase 1 scope cut.)"""
import sys

path = sys.argv[1]
pom = open(path, encoding='utf-8').read()
assert pom.count('<artifactId>platform-starter-messaging-inmemory</artifactId>') == 1
pom = pom.replace('<artifactId>platform-starter-messaging-inmemory</artifactId>', '<artifactId>platform-starter-messaging-rabbit</artifactId>')
added = ''.join('    <dependency>\n      <groupId>ae.gov.dubaicustoms.platform</groupId>\n      <artifactId>%s</artifactId>\n'
                '    </dependency>\n' % artifact
                for artifact in ('platform-starter-messaging-outbox',))
head, tail = pom.rsplit('</dependencies>', 1)
open(path, 'w', encoding='utf-8').write(head.rstrip() + '\n' + added + '  </dependencies>' + tail)
