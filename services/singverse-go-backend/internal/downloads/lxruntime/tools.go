package lxruntime

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"math/big"
	"strings"
	"unicode/utf8"
)

func tool(operation, input string) (string, error) {
	if len(input) > maxBody {
		return "", ErrInvalid
	}
	var data map[string]any
	if json.Unmarshal([]byte(input), &data) != nil {
		return "", ErrInvalid
	}
	var result any
	var err error
	switch operation {
	case "from":
		result, err = toolBytes(data["value"], text(data["encoding"], "utf8"))
	case "string":
		var b []byte
		b, err = toolBytes(data["value"], "utf8")
		if err == nil {
			result, err = toolString(b, text(data["encoding"], "utf8"))
		}
	case "md5", "sha256":
		var b []byte
		b, err = toolBytes(data["value"], "utf8")
		if operation == "md5" {
			h := md5.Sum(b)
			result = hex.EncodeToString(h[:])
		} else {
			h := sha256.Sum256(b)
			result = hex.EncodeToString(h[:])
		}
	case "random":
		size, ok := data["size"].(float64)
		if !ok || size < 0 || size > 1<<20 || size != float64(int(size)) {
			return "", ErrInvalid
		}
		b := make([]byte, int(size))
		_, err = rand.Read(b)
		result = b
	case "aes":
		result, err = toolAES(data)
	case "rsa":
		result, err = toolRSA(data)
	default:
		return "", ErrInvalid
	}
	if err != nil {
		return "", ErrInvalid
	}
	encoded, err := json.Marshal(result)
	if err != nil || len(encoded) > maxBody {
		return "", ErrInvalid
	}
	return string(encoded), nil
}

func text(v any, fallback string) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fallback
}
func flag(v any) bool { b, _ := v.(bool); return b }
func toolBytes(value any, encoding string) ([]byte, error) {
	switch v := value.(type) {
	case map[string]any:
		return toolBytes(v["data"], text(v["encoding"], encoding))
	case []any:
		if len(v) > maxBody {
			return nil, ErrInvalid
		}
		b := make([]byte, len(v))
		for i, n := range v {
			number, ok := n.(float64)
			if !ok {
				return nil, ErrInvalid
			}
			b[i] = byte(int64(number))
		}
		return b, nil
	case nil:
		return []byte{}, nil
	case string:
		switch normalizedEncoding(encoding) {
		case "base64":
			return base64.StdEncoding.DecodeString(v)
		case "hex":
			return hex.DecodeString(v)
		case "binary", "latin1":
			runes := []rune(v)
			b := make([]byte, len(runes))
			for i, r := range runes {
				if r > 255 {
					b[i] = '?'
				} else {
					b[i] = byte(r)
				}
			}
			return b, nil
		default:
			return []byte(v), nil
		}
	default:
		return nil, ErrInvalid
	}
}
func normalizedEncoding(v string) string { return strings.ReplaceAll(strings.ToLower(v), "-", "") }
func toolString(value []byte, encoding string) (string, error) {
	switch normalizedEncoding(encoding) {
	case "base64":
		return base64.StdEncoding.EncodeToString(value), nil
	case "hex":
		return hex.EncodeToString(value), nil
	case "binary", "latin1":
		r := make([]rune, len(value))
		for i, b := range value {
			r[i] = rune(b)
		}
		return string(r), nil
	default:
		if !utf8.Valid(value) {
			return strings.ToValidUTF8(string(value), "\uFFFD"), nil
		}
		return string(value), nil
	}
}
func toolAES(data map[string]any) ([]byte, error) {
	mode := strings.ToLower(text(data["mode"], "aes-128-cbc"))
	legacy, decrypt := flag(data["legacy"]), flag(data["decrypt"])
	if !legacy && mode != "aes-128-cbc" && mode != "aes-128-ecb" {
		return nil, ErrInvalid
	}
	cbc := strings.Contains(mode, "cbc")
	key, err := toolBytes(data["key"], "utf8")
	if err != nil {
		return nil, err
	}
	iv, err := toolBytes(data["iv"], "utf8")
	if err != nil {
		return nil, err
	}
	if (!legacy && len(key) != 16) || (cbc && len(iv) != aes.BlockSize) {
		return nil, ErrInvalid
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	encoding := "utf8"
	if decrypt {
		encoding = "base64"
	}
	input, err := toolBytes(data["value"], encoding)
	if err != nil {
		return nil, err
	}
	padded := legacy || !strings.Contains(mode, "ecb")
	if !decrypt && padded {
		padding := aes.BlockSize - len(input)%aes.BlockSize
		for range padding {
			input = append(input, byte(padding))
		}
	}
	if len(input)%aes.BlockSize != 0 || (decrypt && len(input) == 0) {
		return nil, ErrInvalid
	}
	output := make([]byte, len(input))
	if cbc {
		if decrypt {
			cipher.NewCBCDecrypter(block, iv).CryptBlocks(output, input)
		} else {
			cipher.NewCBCEncrypter(block, iv).CryptBlocks(output, input)
		}
	} else {
		for i := 0; i < len(input); i += aes.BlockSize {
			if decrypt {
				block.Decrypt(output[i:i+aes.BlockSize], input[i:i+aes.BlockSize])
			} else {
				block.Encrypt(output[i:i+aes.BlockSize], input[i:i+aes.BlockSize])
			}
		}
	}
	if decrypt && padded {
		n := int(output[len(output)-1])
		if n < 1 || n > aes.BlockSize || n > len(output) {
			return nil, ErrInvalid
		}
		var difference byte
		for _, b := range output[len(output)-n:] {
			difference |= b ^ byte(n)
		}
		if difference != 0 {
			return nil, ErrInvalid
		}
		output = output[:len(output)-n]
	}
	return output, nil
}
func toolRSA(data map[string]any) ([]byte, error) {
	keyText := text(data["key"], "")
	p, _ := pem.Decode([]byte(keyText))
	var der []byte
	var err error
	if p != nil {
		der = p.Bytes
	} else {
		der, err = base64.StdEncoding.DecodeString(strings.Join(strings.Fields(keyText), ""))
		if err != nil {
			return nil, err
		}
	}
	key, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, err
	}
	public, ok := key.(*rsa.PublicKey)
	if !ok || public.N.BitLen() > 16384 || public.N.BitLen() < 512 {
		return nil, ErrInvalid
	}
	value, err := toolBytes(data["value"], "utf8")
	if err != nil {
		return nil, err
	}
	if flag(data["legacy"]) {
		//lint:ignore SA1019 LX's legacy RSA protocol requires PKCS#1 v1.5 encryption compatibility.
		return rsa.EncryptPKCS1v15(rand.Reader, public, value)
	}
	if len(value) > public.Size() {
		return nil, ErrInvalid
	}
	plain := new(big.Int).SetBytes(value)
	if plain.Cmp(public.N) >= 0 {
		return nil, errors.New("invalid RSA message")
	}
	result := new(big.Int).Exp(plain, big.NewInt(int64(public.E)), public.N)
	return result.FillBytes(make([]byte, public.Size())), nil
}
