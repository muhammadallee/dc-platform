"""100 concurrent chains from two principals. Prints answered, wrong principal, and repeated traces."""
import json
import os
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ["HH_BASE"]
TOKENS = {"principal-a": os.environ["A"], "principal-b": os.environ["B"]}


def one(i):
    who = "principal-a" if i % 2 else "principal-b"
    request = urllib.request.Request(BASE + "/all/orders?ref=b-%03d" % i, method="POST",
                                     headers={"Authorization": "Bearer " + TOKENS[who]})
    with urllib.request.urlopen(request, timeout=90) as response:
        body = json.load(response)
    return body["subject"] == who and body["subjectAfter"] == who, body["trace"]


with ThreadPoolExecutor(20) as pool:
    results = list(pool.map(one, range(100)))
print(len(results), sum(1 for ok, _ in results if not ok), 100 - len({t for _, t in results}))
