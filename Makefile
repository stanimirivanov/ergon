.PHONY: help format compile test verify clean

MVNW := ./mvnw

help:
	@echo "Ergon developer targets"
	@echo "  make format   Format Kotlin sources"
	@echo "  make compile  Compile the complete reactor without tests"
	@echo "  make test     Run the complete reactor test suite"
	@echo "  make verify   Run the required repository verification"
	@echo "  make clean    Remove Maven build output"

format:
	$(MVNW) -B -ntp ktlint:format

compile:
	$(MVNW) -B -ntp -DskipTests compile

test:
	$(MVNW) -B -ntp test

verify:
	$(MVNW) -B -ntp verify

clean:
	$(MVNW) -B -ntp clean
