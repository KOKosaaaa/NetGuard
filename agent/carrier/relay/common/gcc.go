package common

import "sync/atomic"

// GCCTargetBps holds the latest send-side bandwidth estimate (bits/sec) for
// the publisher's video, updated by the GCC interceptor when enabled. 0 means
// unset / no GCC. Exposed for observability and optional writer-side pacing.
var GCCTargetBps atomic.Int64
