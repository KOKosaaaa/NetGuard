package joiner

import "crypto/tls"

// Resolving/dialing an IP must not change the authenticated HTTPS/WSS hostname.
// Android's system certificate directory is configured by common/androidnet.go.
func verifiedSignalingTLS(serverName string) *tls.Config {
	return &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12}
}
