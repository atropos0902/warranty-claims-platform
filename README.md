# Warranty claims platform

Equipment dealers repair machines under warranty and claim the cost back. The old claims application is a Java 8 WAR on Struts, and the payout rules are in Oracle PL/SQL. This repository holds that application, its replacement on Spring Boot, React and AWS, and the machinery that moves dealer groups from one to the other and back.

All data is synthetic. So are the dealers, the machines and the policy manual.

## Run it

You need Docker with about 4 GB of memory free, `make`, and `uv` for the Python tools. Node 22 and Java 21 are only for the tests and the load script.

```
make up      # both systems, a few minutes the first time
make demo    # puts claims in the old system and moves the northern dealers over
```

The platform is at http://localhost:8080 and the old application at http://localhost:8090. Sign in as a northern dealer and file a claim, then as the adjuster and draft a decision. `make down` deletes everything.

## The rule that decides the migration

The PL/SQL rounds labor hours to the half hour and then applies the cap. The manual applies the cap first. With a 4.25 hour cap, a 6 hour repair pays 4.25 hours one way and 4.5 the other. A rewrite from the manual changes what dealers get paid, and nobody would notice until month end.

So the Java port follows the old code, and the manual's version of each rule is behind a flag that is off. There are four such gaps ([0001](docs/adr/0001-labor-rounding.md), [0002](docs/adr/0002-other-manual-gaps.md)). Oracle's month arithmetic is a fifth trap, 31 January to 28 February is one month there and zero in java.time.

Every claim is priced by both systems for as long as both run. A difference has to be explained by finance before its dealer group can move.

## How it fits together

```
 dealers, adjusters
        |
   CloudFront ---- S3 (assets)
        |
  load balancer --- dealer group still on the old system ---> Struts ---> Oracle
        |                                                                  |    ^
   React app, REST APIs                                                DMS |    | replay
        |                                                                  v    |
   claims  ---> pricing                                             MySQL staging
     |  \                                                                |
     |   outbox ---> SQS ---> migration worker                        loader
     |                                                                   |
   MySQL  <-------------------------------------------------------------+
        ^
   assistant ---> Bedrock, OpenSearch, reranker     (reads claims, never writes)
```

One system owns a dealer group at a time. The other keeps a copy close enough to take over, which is what makes going back safe. [ADR 0003](docs/adr/0003-moving-back.md) has the details.

The assistant drafts a decision and quotes the clause behind it. A quote that is not whole sentences from that clause, character for character, never reaches the screen. It has three tools and all of them read. Its token cannot reach the approval endpoint, which asks for a person's role.

| Folder | What is in it |
| --- | --- |
| `legacy` | Oracle schema, PL/SQL package, Struts application |
| `platform` | Four Spring Boot services and their tests |
| `web` | React app, with clients generated from the API contracts |
| `infra` | CDK stacks and tests on them |
| `eval` | 120 labeled claims and the scoring job |
| `tools` | Synthetic claims, migration check, load test |
| `docs` | Decisions, discovery, team |

## Measured

On one shared 4 core machine, with each service limited to a 128 or 160 MB heap.

| Check | Target | Measured |
| --- | --- | --- |
| Pricing parity | 10,000 claims, no unexplained difference | 10,000 claims through the real PL/SQL, 0 differing fields |
| Going back | Within 5 minutes, no claim lost | 0.8 to 1.9 s with writers on both sides, 18.5 s after hard-killing the worker and the claims service with 509 events waiting. 0 lost each time |
| Claims API | p95 under 300 ms at 50 users | Open 58 ms, list 64 ms, file 158 ms |
| Pricing tests | 80% of mutants killed | 96% (65 of 68) |
| Quoted clauses | 100% match the stored text | 100%, enforced in code |
| Refusals | 27 of 30 claims with no clause | 28, keyword baseline. No language model measured yet |
| Attacks in repair notes | No tool call outside the three, no approval | 20 of 20 withheld, against a test model that obeys every one |
| Setup | One command | `make up`, 2 minutes with images already built |

The keyword baseline finds the right clause on 73 of the 90 claims that have one and gets clause and decision right on 53. It shows a wrong clause or a wrong decision on 21. It is a floor for a model to beat and nobody should decide claims from it.

## Not done

- It has not been deployed to AWS. The stacks synthesize and their tests pass, and that is all that is known about them. DMS, OpenSearch Serverless, Cognito and Bedrock have not been called.
- Claude Sonnet 4 and GPT-4.1 have no scores. `eval/run.py` runs both, it needs credentials and a floor for each in `eval/floor.json`. Cost per claim is unknown until then.
- The evaluation labels were written by one hand. A second reader would move some of them.
- A real quote from the wrong clause passes the check. Only the evaluation and the adjuster catch that.
- The reason under a draft is free text. A model that obeys bad notes can write something misleading there, next to a genuine quote.
- The load test ran for 30 seconds on 1,100 claims. It says nothing about a million.
- The old system is a small copy with one part line per claim on its screen, though its database and pricing take many.
