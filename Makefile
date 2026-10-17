.DEFAULT_GOAL := help

MVNW := ./mvnw

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

## Local dependencies

.PHONY: up
up: ## Start Postgres (:5432) and Mailpit (:1025, inbox UI :8025) and wait until healthy
	docker compose up -d --wait

.PHONY: down
down: ## Stop the local dependencies, keep their data
	docker compose down

.PHONY: reset
reset: ## Stop the local dependencies and delete their data
	docker compose down -v

## Build and run

.PHONY: run
run: ## Run the server with the local profile (API :7090, management :7091)
	$(MVNW) -pl jburrow-server -am spring-boot:run

.PHONY: verify
verify: ## Unit, architecture and integration tests (Docker required)
	$(MVNW) clean verify

.PHONY: test
test: ## Unit and architecture tests only, no containers
	$(MVNW) test

.PHONY: format
format: ## Apply Spotless formatting
	$(MVNW) spotless:apply

.PHONY: format-check
format-check: ## Check formatting without changing files
	$(MVNW) spotless:check

.PHONY: image
image: ## Build the server container image (not yet available)
	@echo "The server image is not built yet: jburrow-server/Dockerfile does not exist." >&2; exit 1
