"""Moves a dealer group to the platform and back while both sides are being written to, then checks
that every claim either side accepted is in both databases at the same version, status and total.

Needs the local stack. Run through `make migration-check`.
"""
import os
import sys
import threading
import time

from stack import (CLAIMS, GROUP, PRICING, adjuster, call, dealer, legacy_file, legacy_rows, move, ops, oracle, platform_file,
                   platform_rows, same, wait)

FAST = os.environ.get("SEED_CLAIMS", "200")
accepted: set[str] = set()
refused = {"old": 0, "new": 0}
lock = threading.Lock()


def old_writer(stop):
    db = oracle()
    while not stop.is_set():
        no = legacy_file(db)
        with lock:
            if no:
                accepted.add(no)
            else:
                refused["old"] += 1
        time.sleep(0.05)


def new_writer(stop):
    while not stop.is_set():
        (status, c), body = platform_file()
        if status == 201:
            with lock:
                accepted.add(c["claimNo"])
            if len(accepted) % 3 == 0:
                body["laborHours"] = 2.5
                call("PUT", f"{CLAIMS}/api/v1/claims/{c['claimNo']}", body, dealer, If_Match=f'"{c["version"]}"')
            elif len(accepted) % 3 == 1:
                call("POST", f"{CLAIMS}/api/v1/claims/{c['claimNo']}/decision", {"decision": "APPROVED"}, adjuster,
                     If_Match=f'"{c["version"]}"')
        else:
            assert status == 409 and c["code"] == "GROUP_ON_LEGACY", (status, c)
            with lock:
                refused["new"] += 1
        time.sleep(0.05)


def under_load(to):
    stop = threading.Event()
    threads = [threading.Thread(target=w, args=(stop,)) for w in (old_writer, old_writer, new_writer, new_writer)]
    [t.start() for t in threads]
    time.sleep(3)
    took = move(to)
    time.sleep(3)
    stop.set()
    [t.join() for t in threads]
    return took


seed = oracle()
for _ in range(int(FAST)):
    accepted.add(legacy_file(seed))
print(f"seeded {FAST} claims in the old system")
wait("the first comparison", lambda: call("GET", f"{PRICING}/api/v1/pricing/dealer-groups/{GROUP}/readiness", token=ops)[1]["compared"] >= int(FAST))

there = under_load("PLATFORM")
wait("replay to finish", lambda: len(legacy_rows()) == len(platform_rows()))
time.sleep(2)
same()
print(f"to the platform under load: {there:.1f} s, {len(accepted)} claims accepted so far, {refused} refused")

back = under_load("LEGACY")
wait("the copy to finish", lambda: len(legacy_rows()) == len(platform_rows()))
time.sleep(2)
same()
missing = accepted - set(legacy_rows())
print(f"back to the old system under load: {back:.1f} s, {len(accepted)} claims accepted, {refused} refused, {len(missing)} missing")
assert not missing, sorted(missing)[:5]
ready = call("GET", f"{PRICING}/api/v1/pricing/dealer-groups/{GROUP}/readiness", token=ops)[1]
print("readiness", ready)
assert ready["openDifferences"] == 0
assert refused["old"] and refused["new"], "both sides should have been closed for part of the run"
print("PASS")
