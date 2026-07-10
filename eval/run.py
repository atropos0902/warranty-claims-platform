"""Scores drafted decisions against the labeled claims and fails when a score drops.

    python run.py service            the running assistant, whatever model it is set to
    python run.py bedrock|openai     a model called from here through LangChain, same prompt and clauses

Retrieval always comes from the running assistant, so every model sees the same clauses.
"""
import argparse
import json
import os
import pathlib
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).parent
PROMPTS = HERE.parent / "platform/assistant-service/src/main/resources/prompts"
ASSISTANT = os.environ.get("ASSISTANT_URL", "http://localhost:8083")


def call(method, path, body=None):
    req = urllib.request.Request(ASSISTANT + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {os.environ['EVAL_TOKEN']}")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def cases():
    for n, line in enumerate(l for l in (HERE / "claims.txt").read_text().splitlines() if l and l[0] != "#"):
        clause, decision, notes = line.split("|", 2)
        yield {"id": f"E{n + 1:03d}", "clause": None if clause == "-" else clause, "decision": decision, "notes": notes}


def whole_sentences(quote, text):
    """The rule QuoteGuard applies in the service."""
    parts = re.split(r"(?<=\.) (?=[A-Z])", text)
    return any(" ".join(parts[a:b]) == quote for a in range(len(parts)) for b in range(a + 1, len(parts) + 1))


def from_service(case):
    d = call("POST", "/api/v1/assistant/evaluations/draft", {"id": case["id"], "notes": case["notes"]})
    return {"decision": d["decision"], "clause": d["clauseId"], "withheld": d["withheldWhy"], "tokens": (0, 0), "quote": d["quote"]}


def from_langchain(chain, case):
    clauses = call("GET", "/api/v1/assistant/clauses?q=" + urllib.parse.quote(case["notes"]))
    if not clauses:
        return {"decision": None, "clause": None, "withheld": "NO_CLAUSE", "tokens": (0, 0), "quote": None}
    by_id = {c["id"]: c["text"] for c in clauses}
    policy = "\n\n".join(f"{c['id']} {c['title']}\n{c['text']}" for c in clauses)
    answer = chain.invoke({"claim": json.dumps({"claimNo": case["id"]}), "notes": case["notes"].replace("NOTES>>>", ""), "clauses": policy})
    usage = answer["raw"].usage_metadata or {}
    out = answer["parsed"]
    tokens = (usage.get("input_tokens", 0), usage.get("output_tokens", 0))
    if out is None:
        return {"decision": None, "clause": None, "withheld": "MODEL_FAILED", "tokens": tokens, "quote": None}
    problem = None
    if out.decision == "NONE":
        problem = "NO_CLAUSE"
    elif out.clauseId not in by_id:
        problem = "CLAUSE_NOT_SUPPLIED"
    elif not whole_sentences(out.quote, by_id[out.clauseId]):
        problem = "QUOTE_MISMATCH"
    shown = problem is None
    return {"decision": out.decision if shown else None, "clause": out.clauseId if shown else None, "withheld": problem,
            "tokens": tokens, "quote": out.quote}


def chain_for(target):
    from langchain_core.prompts import ChatPromptTemplate
    from pydantic import BaseModel, Field

    class Output(BaseModel):
        decision: str = Field(description="APPROVE, DENY or NONE")
        clauseId: str = Field(description="clause number, or empty for NONE")
        quote: str = Field(description="one whole sentence copied from the clause, or empty for NONE")
        reason: str

    if target == "bedrock":
        from langchain_aws import ChatBedrockConverse
        model = ChatBedrockConverse(model=os.environ["BEDROCK_MODEL"], temperature=0, max_tokens=600)
    else:
        from langchain_openai import ChatOpenAI
        model = ChatOpenAI(model=os.environ.get("OPENAI_MODEL", "gpt-4.1"), temperature=0)
    prompt = ChatPromptTemplate.from_messages([
        ("system", (PROMPTS / "draft-system.txt").read_text()), ("human", (PROMPTS / "draft-user.txt").read_text())])
    return prompt | model.with_structured_output(Output, include_raw=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", choices=["service", "bedrock", "openai"])
    ap.add_argument("--out")
    ap.add_argument("--no-gate", action="store_true")
    args = ap.parse_args()
    chain = None if args.target == "service" else chain_for(args.target)

    rows = []
    for case in cases():
        start = time.perf_counter()
        got = from_service(case) if chain is None else from_langchain(chain, case)
        rows.append({**case, "got": got, "ms": (time.perf_counter() - start) * 1000})

    labeled = [r for r in rows if r["clause"]]
    empty = [r for r in rows if not r["clause"]]
    shown = [r for r in labeled if r["got"]["clause"]]
    price_in, price_out = float(os.environ.get("PRICE_IN", 0)), float(os.environ.get("PRICE_OUT", 0))
    cost = sum(r["got"]["tokens"][0] * price_in + r["got"]["tokens"][1] * price_out for r in rows) / 1e6 / len(rows)
    score = {
        "target": args.target,
        "clauseMatch": sum(r["got"]["clause"] == r["clause"] for r in labeled),
        "decisionMatch": sum(r["got"]["clause"] == r["clause"] and r["got"]["decision"] == r["decision"] for r in labeled),
        "shownButWrong": sum(r["got"]["clause"] != r["clause"] or r["got"]["decision"] != r["decision"] for r in shown),
        "labeled": len(labeled),
        "refused": sum(r["got"]["clause"] is None for r in empty),
        "noClause": len(empty),
        "badQuotes": sum(r["got"]["withheld"] == "QUOTE_MISMATCH" for r in rows),
        "medianMs": round(statistics.median(r["ms"] for r in rows)),
        "costPerClaim": round(cost, 5) if price_in else None,
    }
    print(json.dumps(score, indent=2))
    if args.out:
        pathlib.Path(args.out).write_text(json.dumps({"score": score, "rows": rows}, indent=1))
    if args.no_gate:
        return
    floor = json.loads((HERE / "floor.json").read_text()).get(args.target)
    if floor is None:
        sys.exit(f"No floor recorded for {args.target}. Run once with --no-gate and add one to floor.json.")
    low = [k for k in ("clauseMatch", "decisionMatch", "refused") if score[k] < floor[k]]
    if low:
        sys.exit(f"Dropped below the floor: {', '.join(low)}")


main()
