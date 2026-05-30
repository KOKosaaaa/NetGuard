//go:build arm64

package deploy

import _ "embed"

//go:embed embed/headless-telemost-creator-arm64
var headlessTelemostCreator []byte
