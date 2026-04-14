"""Writes the synthetic claims used for parity. Same seed, same file."""
import datetime as dt
import random
import sys
from decimal import Decimal

COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 10_000
rnd = random.Random(4250)

DEALERS = ["D101", "D102", "D201", "D202", "D301", "D302"]
CAPS = {"HYD-PUMP": "4.25", "HYD-HOSE": "1.50", "ENG-HEAD": "11.75", "ENG-INJ": "3.25",
        "TRN-CLUT": "8.00", "ELE-HARN": "5.75", "ELE-CTRL": "2.00", "CAB-HVAC": "3.75"}
TERMS = {("T40", "S"): (24, 36, 2000), ("T40", "E"): (36, 60, 4000), ("T40", "G"): (24, 72, 6000),
         ("T90", "S"): (24, 42, 2500), ("T90", "E"): (48, 71, 5000), ("T90", "G"): (24, 84, 8000),
         ("H250", "S"): (12, 24, 1200), ("H250", "E"): (24, 47, 3000), ("H250", "G"): (12, 60, 5000)}


def month_end(year, month):
    return dt.date(year + month // 12, month % 12 + 1, 1) - dt.timedelta(days=1)


def add_months(d, n):
    y, m = divmod(d.year * 12 + d.month - 1 + n, 12)
    return dt.date(y, m + 1, min(d.day, month_end(y, m + 1).day))


def dates(full, end):
    bought = dt.date(2015, 1, 1) + dt.timedelta(days=rnd.randrange(1400))
    roll = rnd.random()
    if roll < 0.35:
        bought = month_end(bought.year, bought.month)
    if roll < 0.60:  # land on a coverage edge, give or take a day
        failed = add_months(bought, rnd.choice([full, end, full + 1, end - 1]))
        if rnd.random() < 0.5:
            failed = month_end(failed.year, failed.month)
        failed += dt.timedelta(days=rnd.choice([-1, 0, 0, 1]))
    elif roll < 0.62:
        failed = bought - dt.timedelta(days=rnd.randrange(1, 90))
    else:
        failed = bought + dt.timedelta(days=rnd.randrange(0, end * 32))
    return bought, failed


def labor(cap):
    if rnd.random() < 0.4:  # quarter hours and the edge of the cap
        return max(Decimal("0"), Decimal(cap) + Decimal(rnd.choice(["-0.50", "-0.26", "-0.25", "-0.24", "0", "0.01", "0.25", "1.75"])))
    if rnd.random() < 0.5:
        return Decimal(rnd.randrange(0, 60)) / 4
    return Decimal(rnd.randrange(0, 1600)) / 100


def meter(limit):
    roll = rnd.random()
    if roll < 0.08:
        return ""
    if roll < 0.25:
        return rnd.choice([str(limit), f"{limit}.1", str(limit - 1) + ".9"])
    return f"{rnd.randrange(0, limit * 11 // 10)}.{rnd.randrange(10)}"


def miles():
    roll = rnd.random()
    if roll < 0.2:
        return ""
    if roll < 0.5:
        return str(rnd.choice([0, 25, 25.1, 24.9, 50, 199.9, 200, 200.1, 340]))
    return f"{rnd.randrange(0, 260)}.{rnd.randrange(10)}"


def lines():
    out = []
    for _ in range(rnd.choice([0, 1, 1, 2, 2, 3, 5])):
        qty = rnd.choice([1, 1, 1, 2, 3, 4, 8])
        if rnd.random() < 0.02:
            qty = -1  # returned core
        cost = Decimal(rnd.randrange(50, 24_000_000)) / 10_000 if rnd.random() < 0.6 else Decimal(rnd.choice(["1499.95", "1500.00", "1500.05", "0.05", "750.00", "12.345", "0.0050"]))
        out.append(f"{qty}:{cost}")
    return ";".join(out)


for i in range(1, COUNT + 1):
    (model, kind), (full, end, limit) = rnd.choice(list(TERMS.items()))
    code = rnd.choice(list(CAPS))
    bought, failed = dates(full, end)
    print(",".join([f"S{i:06d}", rnd.choice(DEALERS), model, kind, bought.isoformat(), failed.isoformat(),
                    meter(limit), code, str(labor(CAPS[code])), miles(), lines()]))
