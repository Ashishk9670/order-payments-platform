# One-command developer workflow. `make help` lists the targets.
.DEFAULT_GOAL := help
SHELL := /bin/bash

help: ## show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

test: ## unit + architecture + contract tests + coverage gate (no Docker needed)
	mvn -B verify -DskipITs

it: ## integration tests against real Postgres/Kafka/Redis (needs Docker)
	mvn -B verify

up: ## build and start the whole platform (needs Docker)
	RATE_LIMIT=100000 docker compose --profile apps up --build -d
	./scripts/wait-for-stack.sh
	@echo "storefront: http://localhost:8080   graphql: http://localhost:8081/graphiql"

up-obs: ## ... plus tracing, Prometheus, Alertmanager, Grafana (http://localhost:3000)
	RATE_LIMIT=100000 TRACING_SAMPLING=1.0 docker compose --profile apps --profile observability up --build -d
	./scripts/wait-for-stack.sh

down: ## stop everything and delete volumes
	docker compose --profile apps --profile observability --profile keycloak --profile psp --profile cdc down -v

e2e: ## Playwright API + UI tests against the running stack
	cd e2e && npm ci && npx playwright install chromium && npx playwright test

chaos: ## failure-injection tests (stops/pauses containers; needs the stack from `make up`)
	cd e2e && npx playwright test --project=chaos

load: ## k6 load test against the running stack
	RATE=30 DURATION=1m k6 run load/orders.js

helm: ## lint and render the chart
	helm lint deploy/helm/order-platform --strict
	helm template demo deploy/helm/order-platform > /dev/null

.PHONY: help test it up up-obs down e2e chaos load helm
