package compute

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
)

const pairingProtocol = "singverse-compute-lan-v1"

func (s *Service) identity(ctx context.Context) (*rsa.PrivateKey, []byte, error) {
	s.keysMu.Lock()
	defer s.keysMu.Unlock()
	if s.keys != nil {
		return s.keys, s.publicDER, nil
	}
	var public, sealed string
	err := s.db.QueryRowContext(ctx, query("identity")).Scan(&public, &sealed)
	if errors.Is(err, sql.ErrNoRows) {
		if ctx.Err() != nil {
			return nil, nil, ctx.Err()
		}
		key, e := rsa.GenerateKey(rand.Reader, 3072)
		if e != nil {
			return nil, nil, errors.New("compute identity generation failed")
		}
		if ctx.Err() != nil {
			return nil, nil, ctx.Err()
		}
		pub, e := x509.MarshalPKIXPublicKey(&key.PublicKey)
		if e != nil {
			return nil, nil, e
		}
		priv, e := x509.MarshalPKCS8PrivateKey(key)
		if e != nil {
			return nil, nil, e
		}
		ciphertext, e := s.cipher.Encrypt(base64.StdEncoding.EncodeToString(priv), credentialPurpose)
		if e != nil {
			return nil, nil, errors.New("compute identity encryption failed")
		}
		_, e = s.db.ExecContext(ctx, query("identity-insert"), base64.StdEncoding.EncodeToString(pub), ciphertext)
		if e != nil {
			return nil, nil, e
		}
		err = s.db.QueryRowContext(ctx, query("identity")).Scan(&public, &sealed)
	}
	if err != nil {
		return nil, nil, err
	}
	invalid := errors.New("compute identity and service master key do not match")
	pub, err := base64.StdEncoding.DecodeString(public)
	if err != nil {
		return nil, nil, invalid
	}
	p, err := x509.ParsePKIXPublicKey(pub)
	if err != nil {
		return nil, nil, invalid
	}
	publicKey, ok := p.(*rsa.PublicKey)
	if !ok || publicKey.N.BitLen() != 3072 {
		return nil, nil, invalid
	}
	plain, err := s.cipher.Decrypt(sealed, credentialPurpose)
	if err != nil {
		return nil, nil, invalid
	}
	privateDER, err := base64.StdEncoding.DecodeString(plain)
	if err != nil {
		return nil, nil, invalid
	}
	parsed, err := x509.ParsePKCS8PrivateKey(privateDER)
	if err != nil {
		return nil, nil, invalid
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok || key.N.Cmp(publicKey.N) != 0 || key.E != publicKey.E || key.Validate() != nil {
		return nil, nil, invalid
	}
	s.keys, s.publicDER = key, pub
	return key, pub, nil
}
func (s *Service) clientID(ctx context.Context) (string, error) {
	_, pub, err := s.identity(ctx)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256(pub)
	return hex.EncodeToString(hash[:]), nil
}
func (s *Service) proof(ctx context.Context, nodeID, challenge string) (string, string, error) {
	key, pub, err := s.identity(ctx)
	if err != nil {
		return "", "", err
	}
	hash := sha256.Sum256(pub)
	client := hex.EncodeToString(hash[:])
	digest := sha256.Sum256([]byte(pairingProtocol + "\n" + nodeID + "\n" + challenge + "\n" + client))
	signature, err := rsa.SignPSS(rand.Reader, key, crypto.SHA256, digest[:], &rsa.PSSOptions{SaltLength: 32, Hash: crypto.SHA256})
	if err != nil {
		return "", "", errors.New("compute pairing signature failed")
	}
	return base64.StdEncoding.EncodeToString(pub), base64.StdEncoding.EncodeToString(signature), nil
}
func (s *Service) decryptPairingToken(ctx context.Context, nodeID, challenge, encrypted string) (string, error) {
	invalid := reject(502, "节点配对凭据校验失败，请重新扫描")
	key, _, err := s.identity(ctx)
	if err != nil {
		return "", err
	}
	ciphertext, err := base64.StdEncoding.DecodeString(encrypted)
	if err != nil || len(ciphertext) != 384 {
		return "", invalid
	}
	plain, err := rsa.DecryptOAEP(sha256.New(), rand.Reader, key, ciphertext, []byte(pairingProtocol+":"+nodeID+":"+challenge))
	if err != nil || !validToken(string(plain)) {
		return "", invalid
	}
	return string(plain), nil
}
