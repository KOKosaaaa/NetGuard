package deploy

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// Warmup pre-downloads release archives so a subsequent /xray/deploy
// (or eventually /telemost/deploy) hits the cache and finishes in
// seconds instead of pulling MBs from GitHub on the user's first VPN
// session.
//
// We deliberately fetch only what the agent itself knows how to
// install — there's no point caching binaries we have no deploy code
// for yet. Sing-box + telemost-creator land here as their phases ship.
//
// Triggered async from the install.sh script the Android client uploads
// during SSH bootstrap; the install script runs it nohup'd so it doesn't
// gate the pair handshake on a slow archive fetch.
func Warmup() tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("xray", 10)
		arch := xrayArchSuffix()
		if arch == "" {
			return h.Fail("E_UNSUPPORTED_ARCH",
				"only amd64 / arm64 are supported", false)
		}
		xrayZipURL := fmt.Sprintf(xrayURLTemplate, xrayVersion, arch)
		xrayZipPath := filepath.Join(CacheDir,
			fmt.Sprintf("Xray-linux-%s-%s.zip", arch, xrayVersion))
		h.LogF("warmup xray → %s", xrayZipPath)
		if err := EnsureCachedDownload(ctx, xrayZipURL, xrayZipPath,
			xrayZipSha256ByArch[arch]); err != nil {
			// Warmup failures are non-fatal from the user's POV — they
			// just mean the first real deploy pays the full download
			// cost. Surface the error for diagnostics but don't make
			// the user retry; they didn't initiate it.
			return h.Fail("E_DOWNLOAD", err.Error(), false)
		}
		h.SetStep("done", 100)
		var bytes int64
		if info, err := os.Stat(xrayZipPath); err == nil {
			bytes = info.Size()
		}
		return h.Ok(map[string]any{
			"xray_zip_path":  xrayZipPath,
			"xray_zip_bytes": bytes,
		})
	}
}
