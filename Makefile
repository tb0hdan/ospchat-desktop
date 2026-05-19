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
.PHONY: help info build run dist package uber-jar all release clean install-deb icons

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

icons: ## Regenerate platform icons (.icns/.ico/.png) from icons/icon.svg
	@command -v rsvg-convert >/dev/null || { echo "rsvg-convert not found (apt install librsvg2-bin)"; exit 1; }
	@command -v png2icns     >/dev/null || { echo "png2icns not found (apt install icnsutils)";        exit 1; }
	@command -v convert      >/dev/null || { echo "ImageMagick 'convert' not found";                   exit 1; }
	@cd icons && \
	  for sz in 16 32 48 128 256 512 1024; do rsvg-convert -w $$sz -h $$sz icon.svg -o _icns-$$sz.png; done && \
	  png2icns icon.icns _icns-16.png _icns-32.png _icns-48.png _icns-128.png _icns-256.png _icns-512.png _icns-1024.png && \
	  for sz in 16 24 32 48 64 128 256; do rsvg-convert -w $$sz -h $$sz icon.svg -o _ico-$$sz.png; done && \
	  convert _ico-16.png _ico-24.png _ico-32.png _ico-48.png _ico-64.png _ico-128.png _ico-256.png icon.ico && \
	  cp _icns-512.png icon.png && \
	  rm -f _icns-*.png _ico-*.png

clean: ## gradle clean
	$(GRADLE) clean

tag:
	@echo "Tagging the current version..."
	git tag -a "v$(VERSION)" -m "Release version $(VERSION)"; \
	git push origin "v$(VERSION)"

lint:
	@ktlint src/
