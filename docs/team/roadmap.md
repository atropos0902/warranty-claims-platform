# Roadmap

A lead who codes and four engineers. Each area has a backup who reviews every change in it.

| Area | Engineer | Backup | First pull request |
| --- | --- | --- | --- |
| Pricing and parity | A | B | `OracleMath` with its month table and nothing else |
| Migration | B | A | Loader that copies one claim and skips a moved group |
| Frontend | C | D | Claim list with the status filter |
| Assistant | D | C | `QuoteGuard` with its tests, no model |
| Contracts, security, AWS | Lead | B | One endpoint with its contract check |

The lead takes the pieces that block two people, plus one ticket from the board each week that nobody asked for. Lead code goes through the same review.

## Order

1. Characterization tests against the PL/SQL. Pricing cannot start without them and neither can the flags.
2. Pricing port and the parity run. Contracts for claims in parallel, so the frontend has something to call.
3. Claims service, outbox, loader. First group copied and compared, still owned by the old system.
4. Frontend for dealers. Replay and moving back, tested under load before any real group moves.
5. Assistant for adjusters, behind the quote check from its first day.
6. First group moves. Two weeks of nothing new while it runs.
7. One group at a time after that.

Moving back is in step 4 and not at the end. It was the thing most likely to be dropped when time got short.
