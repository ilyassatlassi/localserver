JAVAC := javac
JAVA := java
SRC_DIR := src
OUT_DIR := out
MAIN_CLASS := com.server.core.Main
CONFIG := config.json

SOURCES := $(shell find $(SRC_DIR) -name "*.java")

.PHONY: help build run test405 test200 clean

help:
	@echo "Targets:"
	@echo "  build   - compile sources into $(OUT_DIR)"
	@echo "  run     - run Main with default args"
	@echo "  test405 - POST / (expect 405 + Allow)"
	@echo "  test200 - GET / (expect 200)"
	@echo "  clean   - remove $(OUT_DIR)"

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(SOURCES)

run: build
	$(JAVA) -cp $(OUT_DIR) $(MAIN_CLASS) $(CONFIG)

test405: build
	$(JAVA) -cp $(OUT_DIR) $(MAIN_CLASS) $(CONFIG) POST /uploads

test200: build
	$(JAVA) -cp $(OUT_DIR) $(MAIN_CLASS) $(CONFIG) GET /

clean:
	@rm -rf $(OUT_DIR)
