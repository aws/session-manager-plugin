package sigv4a

import (
	"crypto/ecdsa"
	"crypto/rand"
	"encoding/asn1"
	"encoding/hex"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	v4internal "github.com/aws/smithy-go/aws-http-auth/internal/v4"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

const (
	accessKey    = "AKISORANDOMAASORANDOM"
	secretKey    = "q+jcrXGc+0zWN6uzclKVhvMmUsIfRPa4rlRandom"
	sessionToken = "TOKEN"
)

type signAll struct{}

func (signAll) IsSigned(string) bool { return true }

type ecdsaSignature struct {
	R, S *big.Int
}

var credsSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
	SessionToken:    "SESSION",
}

var credsNoSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
}

func seekable(v string) io.ReadSeekCloser {
	return readseekcloser{strings.NewReader(v)}
}

func nonseekable(v string) io.ReadCloser {
	return io.NopCloser(strings.NewReader(v)) // io.NopCloser elides Seek()
}

type readseekcloser struct {
	io.ReadSeeker
}

func (readseekcloser) Close() error { return nil }

func verifySignature(key *ecdsa.PublicKey, hash []byte, signature []byte) (bool, error) {
	var sig ecdsaSignature

	_, err := asn1.Unmarshal(signature, &sig)
	if err != nil {
		return false, err
	}

	return ecdsa.Verify(key, hash, sig.R, sig.S), nil
}

func TestDeriveECDSAKeyPairFromSecret(t *testing.T) {
	t.Skip()
	creds := credentials.Credentials{
		AccessKeyID:     accessKey,
		SecretAccessKey: secretKey,
	}
	privateKey, err := derivePrivateKey(creds)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	expectedX := func() *big.Int {
		t.Helper()
		b, ok := new(big.Int).SetString("15D242CEEBF8D8169FD6A8B5A746C41140414C3B07579038DA06AF89190FFFCB", 16)
		if !ok {
			t.Fatalf("failed to parse big integer")
		}
		return b
	}()
	expectedY := func() *big.Int {
		t.Helper()
		b, ok := new(big.Int).SetString("515242CEDD82E94799482E4C0514B505AFCCF2C0C98D6A553BF539F424C5EC0", 16)
		if !ok {
			t.Fatalf("failed to parse big integer")
		}
		return b
	}()

	if privateKey.X.Cmp(expectedX) != 0 {
		t.Errorf("expected % X, got % X", expectedX, privateKey.X)
	}
	if privateKey.Y.Cmp(expectedY) != 0 {
		t.Errorf("expected % X, got % X", expectedY, privateKey.Y)
	}
}

func newRequest(body io.ReadCloser, opts ...func(*http.Request)) *http.Request {
	req, err := http.NewRequest(http.MethodPost, "https://service.region.amazonaws.com", body)
	if err != nil {
		panic(err)
	}

	for _, opt := range opts {
		opt(req)
	}
	return req
}

func TestSignRequest(t *testing.T) {
	for name, tt := range map[string]struct {
		Input                 *SignRequestInput
		Opts                  v4.SignerOption
		ExpectPreamble        string
		ExpectSignedHeaders   string
		ExpectStringToSign    string
		ExpectDate            string
		ExpectToken           string
		ExpectRegionSetHeader string
	}{
		"minimal case, nonseekable": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1", "us-west-1"},
				Time:        time.Unix(0, 0),
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
968265b4e87c6b10c8ec6bcfd63e8002814cb3256d74c6c381f0c31268c80b53`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1,us-west-1",
		},
		"minimal case, seekable": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
6fbe2f6247e506a47694e695d825477af6c604184f775050ce3b83e04674d9aa`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
		"minimal case, no session": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsNoSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
825ea1f5e80bdb91ac8802e832504d1ff1c3b05b7619ffc273a1565a7600ff5a`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "",
			ExpectRegionSetHeader: "us-east-1",
		},
		"explicit unsigned payload": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: v4.UnsignedPayload(),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
69e5041f5ff858ee8f53a30e5f98cdb4c6bcbfe0f8e61b8aba537d2713bf41a4`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
		"explicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: v4internal.Stosha("{}"),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
6fbe2f6247e506a47694e695d825477af6c604184f775050ce3b83e04674d9aa`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
		"sign all headers": {
			Input: &SignRequestInput{
				Request: newRequest(seekable("{}"), func(r *http.Request) {
					r.Header.Set("Content-Type", "application/json")
					r.Header.Set("Foo", "bar")
					r.Header.Set("Bar", "baz")
				}),
				PayloadHash: v4internal.Stosha("{}"),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.HeaderRules = signAll{}
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=bar;content-type;foo;host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
b28cca9faeaa86f4dbfcc3113b05b38f53cd41f41448a41e08e0171cea8ec363`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
		"disable implicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableImplicitPayloadHashing = true
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/dynamodb/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/dynamodb/aws4_request
69e5041f5ff858ee8f53a30e5f98cdb4c6bcbfe0f8e61b8aba537d2713bf41a4`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
		"s3 settings": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "s3",
				RegionSet:   []string{"us-east-1"},
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableDoublePathEscape = true
				o.AddPayloadHashHeader = true
			},
			ExpectPreamble:      "AWS4-ECDSA-P256-SHA256 Credential=AKID/19700101/s3/aws4_request",
			ExpectSignedHeaders: "SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-region-set;x-amz-security-token",
			ExpectStringToSign: `AWS4-ECDSA-P256-SHA256
19700101T000000Z
19700101/s3/aws4_request
3cf4ba7f150421e93dbc22112484147af6e355676d08a75799cfd32424458d7f`,
			ExpectDate:            "19700101T000000Z",
			ExpectToken:           "SESSION",
			ExpectRegionSetHeader: "us-east-1",
		},
	} {
		t.Run(name, func(t *testing.T) {
			opt := tt.Opts
			if opt == nil {
				opt = func(o *v4.SignerOptions) {}
			}
			signer := New(opt)
			if err := signer.SignRequest(tt.Input); err != nil {
				t.Fatalf("expect no err, got %v", err)
			}

			req := tt.Input.Request
			expectSignature(t, req, tt.Input.Credentials,
				tt.ExpectPreamble, tt.ExpectSignedHeaders, tt.ExpectStringToSign,
				tt.ExpectDate, tt.ExpectToken, tt.ExpectRegionSetHeader)
			if host := req.Header.Get("Host"); req.Host != host {
				t.Errorf("expect host header: %s != %s", req.Host, host)
			}
		})
	}
}

// v4a signatures are random because ECDSA itself is random
// to verify the signature, we effectively have to formally verify the other
// side of the ECDSA calculation
//
// note that this implicitly verifies the correctness of derivePrivateKey,
// otherwise signatures wouldn't match
func expectSignature(
	t *testing.T, signed *http.Request, creds credentials.Credentials,
	expectPreamble, expectSignedHeaders string,      // fixed header components
	expectStrToSign string,                          // for manual signature verification
	expectDate, expectToken, expectRegionSet string, // fixed headers
) {
	t.Helper()

	preamble, signedHeaders, signature, err := getSignature(signed)
	if err != nil {
		t.Fatalf("get signature: %v", err)
	}

	if expectPreamble != preamble {
		t.Errorf("preamble:\n%s\n!=\n%s", expectPreamble, preamble)
	}
	if signedHeaders != expectSignedHeaders {
		t.Errorf("signed headers:\n%s\n!=\n%s", expectSignedHeaders, signedHeaders)
	}

	priv, err := derivePrivateKey(creds)
	if err != nil {
		t.Fatalf("derive private key: %v", err)
	}

	ok, err := verifySignature(&priv.PublicKey, v4internal.Stosha(expectStrToSign), signature)
	if err != nil {
		t.Fatalf("verify signature: %v", err)
	}
	if !ok {
		t.Errorf("signature mismatch")
	}
}

func getSignature(r *http.Request) (
	preamble, headers string, signature []byte, err error,
) {
	auth := r.Header.Get("Authorization")
	if len(auth) == 0 {
		err = fmt.Errorf("no authorization header")
		return
	}

	parts := strings.Split(auth, ", ")
	if len(parts) != 3 {
		err = fmt.Errorf("auth header is malformed: %q", auth)
		return
	}

	sigpart := parts[2]
	sigparts := strings.Split(sigpart, "=")
	if len(sigparts) != 2 {
		err = fmt.Errorf("Signature= component is malformed: %s", sigpart)
		return
	}

	sig, err := hex.DecodeString(sigparts[1])
	if err != nil {
		err = fmt.Errorf("decode signature hex: %w", err)
		return
	}

	return parts[0], parts[1], sig, nil
}

type readexploder struct{}

func (readexploder) Read([]byte) (int, error) {
	return 0, fmt.Errorf("readexploder boom")
}

func TestSignRequest_SignStringError(t *testing.T) {
	randReader := rand.Reader
	rand.Reader = readexploder{}
	defer func() { rand.Reader = randReader }()
	s := New()

	err := s.SignRequest(&SignRequestInput{
		Request:     newRequest(http.NoBody),
		PayloadHash: v4.UnsignedPayload(),
		Credentials: credsSession,
	})
	if err == nil {
		t.Fatal("expect error but didn't get one")
	}
	if expect := "readexploder boom"; expect != err.Error() {
		t.Errorf("error mismatch: %v != %v", expect, err.Error())
	}
}
func TestSignRequestQueryString(t *testing.T) {
	signer := New()

	req := newRequest(nil)
	req.URL.RawQuery = "existing=param"

	err := signer.SignRequest(&SignRequestInput{
		Request:              req,
		Credentials:          credsNoSession,
		Service:              "s3",
		RegionSet:            []string{"us-east-1"},
		Time:                 time.Unix(1375315200, 0),
		SignatureType: v4.SignatureTypeQueryString,
	})

	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	// Should not have Authorization header
	if auth := req.Header.Get("Authorization"); auth != "" {
		t.Errorf("expected no Authorization header, got %s", auth)
	}

	// Should have query parameters
	query := req.URL.Query()
	if query.Get("X-Amz-Algorithm") != "AWS4-ECDSA-P256-SHA256" {
		t.Errorf("expected X-Amz-Algorithm=AWS4-ECDSA-P256-SHA256, got %s", query.Get("X-Amz-Algorithm"))
	}
	if !strings.Contains(query.Get("X-Amz-Credential"), "AKID/20130801/s3/aws4_request") {
		t.Errorf("unexpected X-Amz-Credential: %s", query.Get("X-Amz-Credential"))
	}
	if query.Get("X-Amz-Date") != "20130801T000000Z" {
		t.Errorf("expected X-Amz-Date=20130801T000000Z, got %s", query.Get("X-Amz-Date"))
	}
	if query.Get("X-Amz-SignedHeaders") == "" {
		t.Error("expected X-Amz-SignedHeaders to be set")
	}
	if query.Get("X-Amz-Signature") == "" {
		t.Error("expected X-Amz-Signature to be set")
	}

	// Should preserve existing query params
	if query.Get("existing") != "param" {
		t.Errorf("expected existing=param, got existing=%s", query.Get("existing"))
	}
}
func TestSignRequestHeaderDoesNotAlterQueryString(t *testing.T) {
	signer := New()

	req := newRequest(nil)
	req.URL.RawQuery = "existing=param&another=value"
	originalQuery := req.URL.RawQuery

	err := signer.SignRequest(&SignRequestInput{
		Request:              req,
		Credentials:          credsNoSession,
		Service:              "s3",
		RegionSet:            []string{"us-east-1"},
		Time:                 time.Unix(1375315200, 0),
	SignatureType: v4.SignatureTypeHeader,
	})

	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	// Should have Authorization header
	if auth := req.Header.Get("Authorization"); auth == "" {
		t.Error("expected Authorization header to be set")
	}

	// Query string should be unchanged
	if req.URL.RawQuery != originalQuery {
		t.Errorf("expected query string unchanged, got %s, want %s", req.URL.RawQuery, originalQuery)
	}
}
