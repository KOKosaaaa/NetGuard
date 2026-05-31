// provision.go — one-shot "install everything" run kicked off right after
// the agent is paired, so creating a profile later is instant instead of
// paying the xray download/install on first use.
//
// Runs three installs in sequence, each BEST-EFFORT: a failure in one is
// logged and reported but does not abort the others (a box with xray up
// is useful even if, say, the Telemost binary failed to stage). The task
// always finishes "done" with a per-component status map — the app fires
// this fire-and-forget and never blocks the user on it.

package deploy

import (
	"context"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// ProvisionAll installs xray (empty, running), sing-box, and stages
// Telemost. Reuses the existing per-component runners with the shared task
// handle so their progress + logs flow into this one task.
func ProvisionAll(db *storage.DB) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		status := map[string]any{}

		// --- xray: full deploy with no inbound (empty but valid config,
		// service running). First profile then only appends an inbound. ---
		h.LogF("provision: installing xray (empty config)")
		xo := XrayDeploy(db, &DeployXrayRequest{})(ctx, h)
		status["xray"] = xo.Error == nil
		if xo.Error != nil {
			h.LogF("provision: xray FAILED: %s (%s)", xo.Error.Code, xo.Error.Message)
		}

		// --- sing-box: DEFERRED. The installer lives in singbox.go and
		// works, but nothing consumes sing-box yet (profiles go through
		// xray VLESS+Reality), so we don't auto-install it — it would just
		// download ~24 MB and sit idle on every server. Re-add the
		// `SingboxDeploy()` call here when a sing-box-backed feature ships
		// (e.g. Hysteria2 / TUIC profiles for DPI that throttles TCP). ---

		// --- Telemost: stage binary + unit (no rooms; cookies come later). ---
		h.LogF("provision: staging Telemost")
		to := PrepareTelemost()(ctx, h)
		status["telemost"] = to.Error == nil
		if to.Error != nil {
			h.LogF("provision: Telemost FAILED: %s (%s)", to.Error.Code, to.Error.Message)
		}

		// --- stripe-server: exit-side mux for Telemost room striping. Stage
		// + start it so a striping-enabled client has something to dial. It
		// only listens on loopback and is idle until a phone opens pipes. ---
		h.LogF("provision: staging stripe-server")
		so := PrepareStripe()(ctx, h)
		status["stripe"] = so.Error == nil
		if so.Error != nil {
			h.LogF("provision: stripe-server FAILED: %s (%s)", so.Error.Code, so.Error.Message)
		}

		h.LogF("provision: done xray=%v telemost=%v stripe=%v (sing-box deferred)",
			status["xray"], status["telemost"], status["stripe"])
		return h.Ok(status)
	}
}
