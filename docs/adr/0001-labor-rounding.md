# 0001 Labor rounding stays as it is until it is signed

Status: proposed. Owner: finance controller. Not signed.

## What differs

The PL/SQL rounds labor hours to the nearest half hour and then applies the cap for the repair code. Policy clause 2.1 applies the cap first. With a 4.25 hour cap, a 6 hour repair pays 4.25 hours in the old system and 4.5 by the manual.

It shows up only where the cap sits on a quarter hour. Five of the eight repair codes do. In the synthetic claims, which lean hard on edge cases, the manual's order changes 2,447 of 10,000 payouts. The real share will be lower and has to be counted on real claims before anyone signs.

## Who wants what

Expected positions, to confirm in the interviews. Finance keeps the old result, because reserves are built on it. Operations asks for the manual, because dealers quote clause 2.1 back at them. Both would be right about their own part.

## Decision proposed

Ship the old order. The manual's order is behind the flag `cap-before-rounding`, off. Turning it on is a database migration that names this ADR, so it goes through review like any other change.

## What it costs

The platform keeps paying against its own manual. Dealers who noticed before will notice again.
