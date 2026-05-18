# OSPChat Desktop — Makefile
#
# Compose Desktop packages via jpackage, which can only produce installers
# for the HOST OS. There is no cross-compile path:
#   Linux host  → .deb (and .rpm if you switch TargetFormat)
#   macOS host  → .dmg / .pkg
#   Windows host → .msi / .exe
# Producing all three is a CI matrix concern, not a Makefile one.
VERSION    := $(shell cat VERSION)

# ---- Host detection ----------------------------------------------------------
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
    OS_NAME       := Linux
    INSTALLER_EXT := deb
    INSTALLER_DIR := build/compose/binaries/main/deb
endif
ifeq ($(UNAME_S),Darwin)
    OS_NAME       := macOS
    INSTALLER_EXT := dmg
    INSTALLER_DIR := build/compose/binaries/main/dmg
endif
ifeq ($(OS),Windows_NT)
    OS_NAME       := Windows
    INSTALLER_EXT := msi
    INSTALLER_DIR := build/compose/binaries/main/msi
endif

OS_NAME       ?= Unknown
INSTALLER_EXT ?= unknown
INSTALLER_DIR ?= build/compose/binaries/main/unknown

GRADLE        ?= gradle
DIST_DIR      := build/compose/binaries/main/app
UBER_JAR_DIR  := build/compose/jars

.DEFAULT_GOAL := help
.PHONY: help info build run dist package uber-jar all release clean install-deb

# ---- Help --------------------------------------------------------------------

help: ## Show this help
	@echo "OSPChat Desktop — building for $(OS_NAME) (host produces .$(INSTALLER_EXT))"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "Variables:"
	@echo "  GRADLE       Gradle command (default: gradle). Override with GRADLE=./gradlew"
	@echo "               once the wrapper is bootstrapped."

info: ## Print detected host + output paths
	@echo "Host OS         : $(OS_NAME)"
	@echo "Installer ext   : .$(INSTALLER_EXT)"
	@echo "Installer dir   : $(INSTALLER_DIR)"
	@echo "Distributable   : $(DIST_DIR)"
	@echo "Uber JAR dir    : $(UBER_JAR_DIR)"
	@echo "Gradle command  : $(GRADLE)"

# ---- Dev iteration ----------------------------------------------------------

build: ## Compile + assemble the desktop JAR
	$(GRADLE) :desktopJar

run: ## Run the desktop app from sources
	$(GRADLE) :run

# ---- Distribution / packaging -----------------------------------------------

dist: ## Portable directory distribution with bundled JRE
	$(GRADLE) :createDistributable
	@echo "Distributable: $(DIST_DIR)/OSPChat/"
	@ls -1 $(DIST_DIR) 2>/dev/null || true

package: ## Native installer for the host OS (deb / dmg / msi)
	$(GRADLE) :packageDistributionForCurrentOS
	@echo "Installer dir : $(INSTALLER_DIR)/"
	@ls -1 $(INSTALLER_DIR) 2>/dev/null || true

uber-jar: ## Self-contained fat JAR (still requires a JRE to run)
	$(GRADLE) :packageUberJarForCurrentOS
	@ls -1 $(UBER_JAR_DIR) 2>/dev/null || true

# ---- Composite / release ----------------------------------------------------

release: package ## Full release: native installer

all: package uber-jar ## Build every artifact this host can produce

# ---- Convenience ------------------------------------------------------------

install-deb: ## Install the produced .deb (Linux only — needs sudo)
ifeq ($(OS_NAME),Linux)
	@deb=$$(ls $(INSTALLER_DIR)/*.deb 2>/dev/null | head -1); \
	if [ -z "$$deb" ]; then \
	  echo "No .deb found in $(INSTALLER_DIR). Run 'make package' first."; \
	  exit 1; \
	fi; \
	sudo dpkg -i "$$deb"
else
	@echo "install-deb is Linux-only. Host is $(OS_NAME)."
	@exit 1
endif

clean: ## gradle clean
	$(GRADLE) clean

tag:
	@echo "Tagging the current version..."
	git tag -a "v$(VERSION)" -m "Release version $(VERSION)"; \
	git push origin "v$(VERSION)"
