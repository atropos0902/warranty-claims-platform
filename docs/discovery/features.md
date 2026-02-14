# Features, ranked

Minutes saved and effort are estimates to start the conversation. Replace them with numbers from the interviews before anyone plans on them.

| # | Feature | Adjuster minutes saved per claim (estimate) | Effort (engineer weeks, estimate) | Decision |
| --- | --- | --- | --- | --- |
| 1 | Clause quoted next to the claim | 6 | 5 | First release |
| 2 | Payout steps shown | 3 | 1 | First release |
| 3 | Safe double filing | 1 | 1 | First release |
| 4 | Conflict screen with both versions | 1 | 2 | First release |
| 5 | Earlier repairs on the machine | 2 | 1 | Assistant tool only, no screen yet |
| 6 | Work queue by age and amount | 2 | 2 | Second release |
| 7 | Photos on a claim | 1 | 4 | Cut. Dealers already email them, and storage rules are not agreed |
| 8 | Automatic approval under a limit | 4 | 3 | Cut. Finance will not sign it before a year of draft accuracy |
| 9 | Chat with the policy manual | 1 | 3 | Cut. The draft answers the question in place, chat adds a second place to look |
| 10 | Offline filing for field technicians | 0 | 8 | Cut. Service managers file, technicians do not |

## One measure per feature

| Feature | Measure | Query |
| --- | --- | --- |
| Clause quoted | Share of claims where the decision follows the draft | `share_follow` |
| Payout steps | Reopened payout questions per 100 claims | Counted in the call log, no query yet |
| Safe double filing | Filings answered from an earlier key | Service log, code 200 on POST /claims |
| Conflict screen | Stale edits that end in a save | Service log, 412 then 200 on the same claim |

```sql
-- share_follow, run with read access to both schemas
SELECT COUNT(*) AS drafted,
       AVG((d.decision = 'APPROVE') = (c.status = 'APPROVED')) AS share_follow
  FROM assistant.draft d
  JOIN claims.claim c ON c.claim_no = d.claim_no
 WHERE d.status = 'DRAFTED' AND c.status IN ('APPROVED', 'DENIED')
   AND d.id = (SELECT MAX(x.id) FROM assistant.draft x WHERE x.claim_no = d.claim_no);
```
