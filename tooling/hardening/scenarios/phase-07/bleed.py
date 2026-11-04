"""200 concurrent requests from two principals. Prints how many were answered and how many came back as the wrong one."""
import json
import os
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ["HH_BASE"]
TOKENS = {"principal-a": os.environ["A"], "principal-b": os.environ["B"]}


def one(i):
    who = "principal-a" if i % 2 else "principal-b"
    request = urllib.request.Request(BASE + "/probe/async", headers={"Authorization": "Bearer " + TOKENS[who]})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = json.load(response)
    return body == {"here": who, "there": who}


with ThreadPoolExecutor(40) as pool:
    results = list(pool.map(one, range(200)))
print(len(results), results.count(False))
