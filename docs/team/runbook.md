# Runbook

## Move a dealer group

1. `GET /api/v1/pricing/dealer-groups/{code}/readiness`. Open differences must be zero. Finance explains or rejects each one.
2. `POST /api/v1/moves` with the group and `PLATFORM`. Read progress at `/api/v1/moves/{code}`.
3. File one claim as a dealer in that group.

To send it back, the same call with `LEGACY`. A failed move leaves the group where it was. A move that was cut off can be asked for again and carries on.

## A message is in a dead letter queue

Replay: the claim is in MySQL and not in Oracle. The group cannot move back until it is. Read the worker log for that claim number, fix the cause, then redrive the queue. Repeats are safe.

Compare: pricing refused the claim, most often because a dealer, model or repair code is missing from its reference tables. Add it by migration and redrive.

## Replay is late

Check Oracle first. The worker keeps order for each claim and a lock held in Oracle stops only that claim, so a general delay means the database or the network.

## The assistant is down

Nothing to do for claims. Adjusters see a message and decide without a draft. If drafts are withheld more than usual, read `withheld_why` in `assistant.draft`.

## First deployment

1. Deploy Network, Auth and Data. Create the databases and users from `local/mysql-init.sql` with the passwords from Secrets Manager.
2. Fill the Oracle secret: host, port, sid, username, password, and `jdbcUrl` for the worker.
3. Turn on supplemental logging in Oracle for the two claim tables, which DMS needs.
4. Create the ECR repositories `warranty/pricing`, `claims`, `assistant`, `migration` and `web`, and push images.
5. Deploy the rest with `-c bedrockModel=` set to the full model id for the region.
6. Register the addresses of the old application in the Legacy target group.
7. Start claims once before DMS, so Flyway has created the staging tables. Then start the DMS task.
