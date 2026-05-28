# NetGuard Agent — cross-compile targets.
#
# Output goes to dist/netguard-agent-<arch> alongside a .sha256 file used
# by the Android bootstrap script to verify the download.

GO              ?= go
VERSION         := $(shell grep agentVersion cmd/netguard-agent/main.go | head -1 | cut -d'"' -f2)
DIST            := dist
LDFLAGS         := -s -w
BUILD_FLAGS     := -trimpath -ldflags='$(LDFLAGS)'

.PHONY: all linux-amd64 linux-arm64 clean tidy

all: linux-amd64 linux-arm64

linux-amd64:
	@mkdir -p $(DIST)
	GOOS=linux GOARCH=amd64 CGO_ENABLED=0 $(GO) build $(BUILD_FLAGS) \
		-o $(DIST)/netguard-agent-amd64 ./cmd/netguard-agent
	cd $(DIST) && sha256sum netguard-agent-amd64 > netguard-agent-amd64.sha256
	@ls -la $(DIST)/netguard-agent-amd64*

linux-arm64:
	@mkdir -p $(DIST)
	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 $(GO) build $(BUILD_FLAGS) \
		-o $(DIST)/netguard-agent-arm64 ./cmd/netguard-agent
	cd $(DIST) && sha256sum netguard-agent-arm64 > netguard-agent-arm64.sha256
	@ls -la $(DIST)/netguard-agent-arm64*

tidy:
	$(GO) mod tidy

clean:
	rm -rf $(DIST)
