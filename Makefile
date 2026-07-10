.PHONY: up down test mutation parity migration-check load e2e eval infra demo
ORACLE_ENV = LEGACY_DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 LEGACY_DB_USER=claims LEGACY_DB_PASSWORD=claims
PYTHON = uv run -q --no-project --python 3.12

up:               ## start both systems
	docker compose up -d --build --wait
down:             ## stop and delete the local data
	docker compose down -v
test:             ## every Java and web test except the ones that need the stack
	cd platform && mvn -B verify
	cd web && npm ci --no-audit --no-fund && npm run check && npm test
mutation:         ## mutation tests on the pricing rules
	cd platform && mvn -B -pl commons,pricing-service -am verify -Pmutation -DskipITs
parity:           ## 10,000 claims through the real PL/SQL and the Java port
	cd platform && $(ORACLE_ENV) mvn -B -pl commons,pricing-service -am test -Dtest=OracleParityIT -Dsurefire.failIfNoSpecifiedTests=false
migration-check:  ## move a dealer group there and back under load
	$(PYTHON) --with oracledb==3.3.0 python tools/migration_check.py
demo:             ## seed the old system and move the northern dealers to the platform
	$(PYTHON) --with oracledb==3.3.0 python tools/demo.py
load:             ## latency at fifty users (run after demo)
	node tools/load.mjs
e2e:              ## browser tests (run after demo)
	cd web && npm ci --no-audit --no-fund && npx playwright install chromium && WEB_URL=http://localhost:8080 npx playwright test
eval:             ## score the running assistant on the labeled claims
	EVAL_TOKEN=$$(python3 tools/eval_token.py) python3 eval/run.py service
infra:            ## check and synthesize the AWS stacks
	cd infra && npm ci --no-audit --no-fund && npm run check && npm test && npm run synth
