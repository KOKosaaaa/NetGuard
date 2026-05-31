//go:build arm64

package deploy

import _ "embed"

//go:embed embed/stripe-server-arm64
var stripeServerBin []byte
