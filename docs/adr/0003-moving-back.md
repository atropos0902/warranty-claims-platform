# 0003 A dealer group can go back

Status: accepted.

## Problem

A one-way migration makes the first group a bet. If the platform pays wrong in week two, the claims filed since then exist only there.

## Decision

One system owns a group at a time. While the old system owns it, DMS copies changes into MySQL staging and the loader brings them over. Once the platform owns it, every change leaves through the outbox and is replayed into Oracle. The old database is never more than a queue behind, so it can take the group back.

The owner column has four values. MOVING and RETURNING close both sides for a moment, long enough to check that they hold the same claims at the same versions. If they do not agree in five minutes the group stays where it was.

Three rules keep it honest.

- The loader skips groups the platform owns. Their rows in staging are echoes of our own replay and some are stale.
- Replay checks an inbox and a version, so repeats and late messages do nothing.
- Every writer takes a shared lock on the group row, so the owner cannot change under a write in flight. Oracle has the same lock in a trigger.

## Rejected

Dual writes from the application. There is no transaction across both databases, and the failure is silent.

Reverse DMS. It would copy the staging echo around in a loop, and it knows nothing about versions.

## What it costs

Oracle stays licensed until the last group has been on the platform long enough. Moves are refused writes for a second or two.
