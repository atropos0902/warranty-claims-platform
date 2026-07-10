# Review guide

- A change should be readable in one sitting. If it needs a tour, split it.
- Payout code: which characterization test covers this line? If none does, write it first.
- Anything that writes a claim: does it lock the group before the claim, and does it emit an event in the same transaction?
- Anything a queue delivers: what happens the second time?
- An endpoint change comes with its contract file. `OPENAPI_WRITE=true` rewrites it.
- Prompts and the manual are code. A change to either shows the evaluation score before and after.
- Tests drafted with Copilot have to kill mutants under PIT. Add a line to the log when one did not.
- Approve with comments when the comments are opinions. Block when someone could lose a claim or be paid wrong.

# Copilot log

| What it was used for | Helped | Did not |
| --- | --- | --- |

Empty on purpose. Fill it in as work happens.

# Definition of done

Tests that fail without the change. Contract current. Alarm or log line for the new way it can break. Runbook entry if a person has to do something. Shown to someone who will use it.
