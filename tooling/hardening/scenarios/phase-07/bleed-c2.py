"""200 concurrent chains from two principals. Prints answered, wrong principal, and repeated traces."""
import json
import os
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ["HH_BASE"]
TOKENS = {"principal-a": os.environ["A"], "principal-b": os.environ["B"]}


def one(i):
    who = "principal-a" if i % 2 else "principal-b"
    request = urllib.request.Request(BASE + "/c2/orders", method="POST", headers={"Authorization": "Bearer " + TOKENS[who]})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.load(response)
    return body["subject"] == who and body["subjectAfter"] == who, body["trace"]


with ThreadPoolExecutor(30) as pool:
    results = list(pool.map(one, range(200)))
print(len(results), sum(1 for ok, _ in results if not ok), 200 - len({t for _, t in results}))
