//go:build amd64

package deploy

import _ "embed"

//go:embed embed/stripe-server-amd64
var stripeServerBin []byte
