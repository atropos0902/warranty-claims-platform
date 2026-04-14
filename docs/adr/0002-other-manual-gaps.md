# 0002 Three more places where the old code and the manual disagree

Status: proposed. Owner: finance controller. Not signed.

Characterization tests against the PL/SQL turned these up. Each one has a flag, off.

| Gap | Old code | Manual | Flag | Synthetic payouts changed |
| --- | --- | --- | --- | --- |
| Missing meter reading | Pays, because a comparison with NULL is never true | Clause 1.4 holds the claim | `hold-missing-meter` | 684 of 10,000 |
| Handling limit | 150 for each part line | Clause 3.2 says 150 for the claim | `handling-limit-per-claim` | 3,481 of 10,000 |
| Failure before purchase | Pays in full, months in service are negative | Not written down, clearly a typing error | `reject-failure-before-purchase` | 203 of 10,000 |

One more trap is not a policy gap. Oracle counts 31 January to 28 February as one month and java.time counts zero. The two disagree on 403 of the 10,000 claims, so the port carries its own month arithmetic.

The last row of the table is the one to sign first. There is no case for paying it.
