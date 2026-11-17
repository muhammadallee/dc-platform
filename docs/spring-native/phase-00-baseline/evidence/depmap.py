"""Phase 0 step 5: which kept POMs reference a cut module or a library only cut modules need."""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
CUT_DIR = re.compile(r'(kafka|redis|audit|idempot|ratelimit|storage|(^|/)files|flags)')
CUT_REF = re.compile(
    r'<artifactId>(platform-[a-z-]*(audit|idempotency|redis|ratelimit|storage|files|flags|kafka)[a-z-]*'
    r'|lettuce-core|kafka-clients|spring-kafka|spring-data-redis|[a-z-]*openfeature[a-z-]*|s3|aws-[a-z-]+)</artifactId>'
    r'|<module>[^<]*(kafka|redis|audit|idempotency|ratelimit|storage|files|flags)[^<]*</module>')

for pom in sorted(root.rglob('pom.xml')):
    rel = pom.parent.relative_to(root).as_posix()
    if '/target/' in f'/{rel}/' or rel.startswith('docs/spring-native') or rel.startswith(('scratch', 'tmpviol')):
        continue
    if CUT_DIR.search(rel):
        continue
    hits = sorted({m.group(0) for m in CUT_REF.finditer(pom.read_text(encoding='utf-8'))})
    if hits:
        print(f'{rel or "."}: {" ".join(hits)}')
