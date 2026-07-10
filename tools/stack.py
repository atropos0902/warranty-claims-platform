"""Calls into the local stack, shared by the checks and the demo."""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

import oracledb

CLAIMS = os.environ.get("CLAIMS_URL", "http://localhost:8081")
PRICING = os.environ.get("PRICING_URL", "http://localhost:8082")
MIGRATION = os.environ.get("MIGRATION_URL", "http://localhost:8084")
ORACLE = os.environ.get("ORACLE_DSN", "localhost:1521/FREEPDB1")
GROUP, DEALER = "NORTH", "D101"


def call(method, url, body=None, token=None, **headers):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for k, v in headers.items():
        req.add_header(k.replace("_", "-"), v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read()
            return r.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read()
        return e.code, json.loads(text) if text else None


def token(user, *groups):
    return call("POST", f"{CLAIMS}/dev/token", {"user": user, "groups": list(groups)})[1]["token"]


dealer = token("dealer", "DEALER", f"GROUP_{GROUP}")
adjuster = token("adjuster", "ADJUSTER")
ops = token("ops", "OPS")


def oracle():
    return oracledb.connect(user="claims", password="claims", dsn=ORACLE)


def legacy_file(db, dealer_code=DEALER):
    """Files one claim the way the old application does. Returns its number, or None when the group is closed."""
    no = "L" + uuid.uuid4().hex[:12].upper()
    try:
        with db.cursor() as c:
            c.execute(
                "INSERT INTO claims (claim_no, dealer_code, model_code, serial_no, claim_type, purchase_date, failure_date,"
                " meter_hours, repair_code, labor_hours, travel_miles, notes, line_count)"
                " VALUES (:1, :2, 'T40', 'SN-5', 'S', DATE '2017-01-31', DATE '2017-06-30', NULL, 'HYD-PUMP', 6, 40.5, 'old screen', 1)",
                [no, dealer_code])
            c.execute("INSERT INTO claim_lines VALUES (:1, 1, 'HP-100', 2, 812.3456)", [no])
            c.callproc("claim_pricing.price_claim", [no])
        db.commit()
        return no
    except oracledb.DatabaseError as e:
        db.rollback()
        if e.args[0].code == 20010:
            return None
        raise


def platform_file():
    body = {"dealerCode": DEALER, "modelCode": "T90", "serialNo": "SN-6", "claimType": "E", "purchaseDate": "2016-02-29",
            "failureDate": "2018-02-28", "meterHours": 120.5, "repairCode": "ENG-INJ", "laborHours": 3.74,
            "travelMiles": None, "notes": "new screen", "lines": [{"partNo": "INJ-4", "qty": 3, "unitCost": 99.9999}]}
    return call("POST", f"{CLAIMS}/api/v1/claims", body, dealer, Idempotency_Key=uuid.uuid4().hex), body


def wait(what, check, seconds=120):
    start = time.time()
    while time.time() - start < seconds:
        if check():
            return
        time.sleep(0.5)
    sys.exit(f"FAIL: timed out waiting for {what}")


def move(to):
    start = time.time()
    status, body = call("POST", f"{MIGRATION}/api/v1/moves", {"groupCode": GROUP, "to": to}, ops)
    assert status == 202, (status, body)
    while True:
        _, p = call("GET", f"{MIGRATION}/api/v1/moves/{GROUP}", token=ops)
        if p["state"] != "RUNNING":
            assert p["state"] == "DONE", p
            return time.time() - start
        time.sleep(0.2)


def legacy_rows():
    with oracle() as db, db.cursor() as c:
        c.execute("SELECT c.claim_no, version_no, status, total_pay FROM claims c JOIN dealers d ON d.dealer_code = c.dealer_code"
                  " WHERE group_code = :1", [GROUP])
        return {r[0]: (int(r[1]), r[2], round(float(r[3]), 2)) for r in c}


def platform_rows():
    out, after = {}, ""
    while True:
        _, page = call("GET", f"{CLAIMS}/api/v1/claims?after={after}", token=dealer)
        if not page:
            return out
        for s in page:
            _, c = call("GET", f"{CLAIMS}/api/v1/claims/{s['claimNo']}", token=dealer)
            out[c["claimNo"]] = (c["version"], c["status"], round(c["price"]["totalPay"], 2))
        after = page[-1]["claimNo"]


def same():
    a, b = legacy_rows(), platform_rows()
    return a == b or sys.exit(f"FAIL: {sorted(set(a.items()) ^ set(b.items()))[:6]}")
