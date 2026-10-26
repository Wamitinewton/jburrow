.DEFAULT_GOAL := help

MVNW := ./mvnw
# The reactor version: the first <version> after jburrow-parent's artifactId in the root POM.
VERSION := $(shell awk '/<artifactId>jburrow-parent<\/artifactId>/{f=1} f && /<version>/{gsub(/.*<version>|<\/version>.*/, ""); print; exit}' pom.xml)

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

.PHONY: authtoken
authtoken: ## Print a new authtoken and its jburrow.auth.static-tokens entry: make authtoken NAME=alice
	@test -n "$(NAME)" || (echo "usage: make authtoken NAME=<name>" >&2; exit 2)
	@python3 -c 'import hashlib,secrets,sys; t="jb_"+secrets.token_urlsafe(32); print("token: "+t); print("entry: "+sys.argv[1]+":"+hashlib.sha256(t.encode()).hexdigest())' "$(NAME)"

.PHONY: verify
verify: ## Unit, architecture and integration tests (Docker required)
	$(MVNW) clean verify

.PHONY: test
test: ## Unit and architecture tests only, no containers
	$(MVNW) test

# Jazzer fuzzes only one @FuzzTest per JVM, so each target gets its own run.
FUZZ_TARGETS := FrameCodecFuzzTest\#decodeAsServer FrameCodecFuzzTest\#decodeAsClient \
	ControlJsonFuzzTest\#read StreamLifecycleFuzzTest\#events SessionFlowFuzzTest\#peers

.PHONY: fuzz
fuzz: ## Fuzz each protocol target for its maxDuration (2 min); findings land in its inputs directory
	@status=0; for target in $(FUZZ_TARGETS); do \
	  echo "==> Fuzzing $$target"; \
	  JAZZER_FUZZ=1 $(MVNW) -B --no-transfer-progress -pl jburrow-protocol test \
	    -Dtest="$$target" -Dsurefire.failIfNoSpecifiedTests=false -Djazzer.valueprofile=true \
	    -DargLine="-Xmx1g -Xss1m" || status=1; \
	done; exit $$status

.PHONY: native
native: ## Build the native CLI binary jburrow-cli/target/jburrow (GraalVM for JDK 21 as the JDK)
	$(MVNW) -Pnative -pl jburrow-cli -am package -DskipTests

.PHONY: native-smoke
native-smoke: native ## Run a real tunnel through the native CLI against the JVM server (Docker required)
	$(MVNW) -pl jburrow-server -am package -DskipTests
	scripts/native-smoke.sh

.PHONY: format
format: ## Apply Spotless formatting
	$(MVNW) spotless:apply

.PHONY: format-check
format-check: ## Check formatting without changing files
	$(MVNW) spotless:check

.PHONY: image
image: ## Build the server container image as jburrow-server:dev
	docker build -f jburrow-server/Dockerfile \
	  --build-arg GIT_COMMIT=$$(git rev-parse --short HEAD) \
	  --build-arg VERSION=$(VERSION) \
	  -t jburrow-server:dev .
