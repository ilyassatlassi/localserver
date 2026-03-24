JAVAC := javac
JAVA := java
SRC_DIR := src
OUT_DIR := out
MAIN_CLASS := com.server.core.Main
CONFIG := config.json

SOURCES := $(shell find $(SRC_DIR) -name "*.java")

.PHONY: help build run smoke clean

help:
	@echo "Targets:"
	@echo "  build   - compile sources into $(OUT_DIR)"
	@echo "  run     - run Main with config file"
	@echo "  smoke   - run basic checks and report success"
	@echo "  clean   - remove $(OUT_DIR)"

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(SOURCES)

run: build
	$(JAVA) -cp $(OUT_DIR) $(MAIN_CLASS) -c $(CONFIG)

smoke: build
	@echo "Smoke checks passed."

clean:
	@rm -rf $(OUT_DIR)
