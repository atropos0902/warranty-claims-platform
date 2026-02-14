#!/bin/bash
# Runs inside the Oracle container at first start, as the application user.
set -e
cd /opt/claims-db
for f in 01_schema.sql 02_claim_pricing.sql 03_reference.sql; do
  printf 'WHENEVER SQLERROR EXIT FAILURE\nSET DEFINE OFF\n@%s\nEXIT\n' "$f" \
    | sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost/FREEPDB1"
done
