SHELL := /bin/bash
SRE_DIR ?= ../sre_workflows
GATEWAY ?= http://localhost:8080
SRE_NETWORK ?= sre_workflows_default
SERVICES := gateway login-service funds-service balance-service user-service profile-service insights-service

.PHONY: build up down logs ps test e2e seed load-baseline load-ramp load-spike load-latency load-throttle load-soak \
        load-journey faults-on faults-off faults-status check-sre clean

build:                 ## package service jars + fetch the OTel agent and baggage-processor extension
	./gradlew downloadOtelAgent $(foreach s,$(SERVICES),:services:$(s):bootJar)

# Export to the sre_workflows collector when its network exists, else run a local collector + Jaeger.
SRE_UP := $(shell docker network inspect $(SRE_NETWORK) >/dev/null 2>&1 && echo yes)
COMPOSE := docker compose $(if $(SRE_UP),-f docker-compose.yml -f docker-compose.sre.yml,--profile standalone)

check-sre:
	@$(if $(SRE_UP),echo "telemetry -> sre_workflows otel-collector ($(SRE_NETWORK))",\
	  echo "sre_workflows not running: using standalone collector + Jaeger http://localhost:16686 (start it with make -C $(SRE_DIR) up, then make up)")

up: check-sre build    ## build and start the whole demo stack
	$(COMPOSE) up -d --build --wait

down:
	docker compose --profile standalone down

clean:                 ## stop and drop volumes (Kafka topics, Postgres data)
	docker compose --profile standalone down -v

logs:
	$(COMPOSE) logs -f --tail=100 $(SERVICES)

ps:
	$(COMPOSE) ps

test:                  ## unit + Testcontainers integration tests
	./gradlew test

e2e:                   ## against the running stack: one pass per journey, asserts journey traces in Tempo
	./gradlew :load-tests:e2eTest -Dgateway=$(GATEWAY)

USERS ?= 500
seed:                  ## create mock users/profiles/accounts through the API (Datafaker) -> users.csv
	./gradlew :load-tests:seed -Dgateway=$(GATEWAY) -Dusers=$(USERS)

# ---- Gatling. Override: make load-baseline TPS=80 DURATION=300 ----
GATLING = ./gradlew :load-tests:gatlingRun -Dgateway=$(GATEWAY) $(if $(TPS),-Dtps=$(TPS)) $(if $(DURATION),-Dduration=$(DURATION))
JOURNEY ?= add_funds
load-baseline: ; $(GATLING) --simulation demo.load.BaselineSimulation
load-ramp:     ; $(GATLING) --simulation demo.load.RampSimulation
load-spike:    ; $(GATLING) --simulation demo.load.SpikeSimulation
load-latency:  ; $(GATLING) --simulation demo.load.VendorLatencySimulation $(if $(VENDOR),-Dvendor=$(VENDOR))
load-throttle: ; $(GATLING) --simulation demo.load.ThrottleSimulation
load-soak:     ; $(GATLING) --simulation demo.load.SoakSimulation
load-journey:  ; $(GATLING) --simulation demo.load.JourneySimulation -Djourney=$(JOURNEY)

# ---- vendor faults (WireMock). make faults-on VENDOR=paylane DELAY=5000 PCT=20 [ERRORS=10] ----
VENDOR ?= paylane
DELAY ?= 5000
PCT ?= 100
faults-on:
	scripts/faults.sh latency $(VENDOR) $(DELAY) $(PCT)
	$(if $(ERRORS),scripts/faults.sh errors $(VENDOR) $(ERRORS))
faults-off:
	scripts/faults.sh reset
faults-status:
	scripts/faults.sh status
