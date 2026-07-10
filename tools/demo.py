"""Puts a few claims in the old system and moves the northern dealers to the platform."""
import time

from stack import GROUP, PRICING, call, legacy_file, move, ops, oracle, wait

with oracle() as db:
    for dealer_code in ["D101", "D102", "D101", "D201", "D301"] * 4:
        legacy_file(db, dealer_code)
wait("the comparisons", lambda: call("GET", f"{PRICING}/api/v1/pricing/dealer-groups/{GROUP}/readiness", token=ops)[1]["compared"] > 0)
time.sleep(3)
print(f"{GROUP} is on the platform, took {move('PLATFORM'):.1f} s")
