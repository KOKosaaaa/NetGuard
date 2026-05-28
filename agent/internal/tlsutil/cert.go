// Package tlsutil handles the agent's self-signed TLS identity.
//
// We generate one ECDSA P-256 keypair on first start, write it to the agent's
// state dir (mode 0600), and serve HTTPS with a self-signed cert wrapping it.
// The Android app pins the SubjectPublicKeyInfo hash (SPKI pin), not the cert
// itself, so we can regenerate the cert later (rotation, hostname change)
// without breaking pairing as long as the private key is preserved.
package tlsutil

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// Identity is the agent's TLS material in a form ready for crypto/tls.
type Identity struct {
	CertPEM []byte
	KeyPEM  []byte
	// SPKIHash is sha256 over the DER-encoded SubjectPublicKeyInfo of the
	// public key. This is what the Android client pins.
	SPKIHash [32]byte
}

// LoadOrGenerate returns an Identity from dir/{key.pem,cert.pem}, creating
// fresh ones if either file is missing. Safe to call repeatedly.
func LoadOrGenerate(dir string) (*Identity, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("mkdir %s: %w", dir, err)
	}
	keyPath := filepath.Join(dir, "key.pem")
	certPath := filepath.Join(dir, "cert.pem")

	keyPEM, keyErr := os.ReadFile(keyPath)
	certPEM, certErr := os.ReadFile(certPath)
	if keyErr == nil && certErr == nil {
		return parseIdentity(certPEM, keyPEM)
	}

	// One or both missing — regenerate the pair atomically.
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("ecdsa generate: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "netguard-agent"},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		return nil, fmt.Errorf("create cert: %w", err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, fmt.Errorf("marshal key: %w", err)
	}

	newCertPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	newKeyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})

	// Atomic: write to tmp then rename. Key first, then cert — that way if we
	// crash between writes the next start will treat both as missing and
	// regenerate cleanly, rather than load a key + stale cert combo.
	if err := writeAtomic(keyPath, newKeyPEM, 0o600); err != nil {
		return nil, err
	}
	if err := writeAtomic(certPath, newCertPEM, 0o644); err != nil {
		return nil, err
	}
	return parseIdentity(newCertPEM, newKeyPEM)
}

func parseIdentity(certPEM, keyPEM []byte) (*Identity, error) {
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil {
		return nil, errors.New("cert.pem: no PEM block")
	}
	cert, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse cert: %w", err)
	}
	spkiDER, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("marshal SPKI: %w", err)
	}
	return &Identity{
		CertPEM:  certPEM,
		KeyPEM:   keyPEM,
		SPKIHash: sha256.Sum256(spkiDER),
	}, nil
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		return fmt.Errorf("write %s: %w", tmp, err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("rename %s -> %s: %w", tmp, path, err)
	}
	return nil
}
