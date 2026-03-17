package sigv4

import (
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	v4internal "github.com/aws/smithy-go/aws-http-auth/internal/v4"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

var credsSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
	SessionToken:    "SESSION",
}

var credsNoSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
}

type signAll struct{}

func (signAll) IsSigned(string) bool { return true }

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

func newRequest(body io.ReadCloser, opts ...func(*http.Request)) *http.Request {
	// we initialize via NewRequest because it sets basic things like host and
	// proto and is generally how we recommend the signing APIs are used
	//
	// the url doesn't actually need to match the signing name / region
	req, err := http.NewRequest(http.MethodPost, "https://service.region.amazonaws.com", body)
	if err != nil {
		panic(err)
	}

	for _, opt := range opts {
		opt(req)
	}
	return req
}

func expectSignature(t *testing.T, signed *http.Request, expectSignature, expectDate, expectToken string) {
	if actual := signed.Header.Get("Authorization"); expectSignature != actual {
		t.Errorf("expect signature:\n%s\n!=\n%s", expectSignature, actual)
	}
	if actual := signed.Header.Get("X-Amz-Date"); expectDate != actual {
		t.Errorf("expect date: %s != %s", expectDate, actual)
	}
	if actual := signed.Header.Get("X-Amz-Security-Token"); expectToken != actual {
		t.Errorf("expect token: %s != %s", expectToken, actual)
	}
}

func TestSignRequest(t *testing.T) {
	for name, tt := range map[string]struct {
		Input           *SignRequestInput
		Opts            v4.SignerOption
		ExpectSignature string
		ExpectDate      string
		ExpectToken     string
	}{
		"minimal case, nonseekable": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"minimal case, seekable": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=e75efbd4e2b3d3a8218d8fc0125e8fc888844510125ca6f33be555fd76d9aa18",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"minimal case, no session": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsNoSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date, Signature=6f249a4b86fd230f28cae603cdf92c2657b1d1ffc3fcccbd938e1339c4542e14",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "",
		},
		"explicit unsigned payload": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: v4.UnsignedPayload(),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"explicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: v4internal.Stosha("{}"),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=e75efbd4e2b3d3a8218d8fc0125e8fc888844510125ca6f33be555fd76d9aa18",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
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
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.HeaderRules = signAll{}
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=bar;content-type;foo;host;x-amz-date;x-amz-security-token, Signature=90673d8f57147fd36dbde4d4fe156f643ea25627e7b4d14c157c6369e685b80a",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"disable implicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableImplicitPayloadHashing = true
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"s3 settings": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "s3",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableDoublePathEscape = true
				o.AddPayloadHashHeader = true
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=0232da513d9e9830b12cf0d9f374834671494bc362ee173adb2a50267d0339e0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
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
			expectSignature(t, req, tt.ExpectSignature, tt.ExpectDate, tt.ExpectToken)
			if host := req.Header.Get("Host"); req.Host != host {
				t.Errorf("expect host header: %s != %s", req.Host, host)
			}
		})
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
		Region:               "us-east-1",
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
	if query.Get("X-Amz-Algorithm") != "AWS4-HMAC-SHA256" {
		t.Errorf("expected X-Amz-Algorithm=AWS4-HMAC-SHA256, got %s", query.Get("X-Amz-Algorithm"))
	}
	if !strings.Contains(query.Get("X-Amz-Credential"), "AKID/20130801/us-east-1/s3/aws4_request") {
		t.Errorf("unexpected X-Amz-Credential: %s", query.Get("X-Amz-Credential"))
	}
	if query.Get("X-Amz-Date") != "20130801T000000Z" {
		t.Errorf("expected X-Amz-Date=20130801T000000Z, got %s", query.Get("X-Amz-Date"))
	}
	if query.Get("X-Amz-SignedHeaders") != "host" {
		t.Errorf("expected X-Amz-SignedHeaders=host, got %s", query.Get("X-Amz-SignedHeaders"))
	}
	expectedSignature := "16e17486c8e6bb97596d140876d8a23164a13552e644900b81dba01ad092bdac"
	if query.Get("X-Amz-Signature") != expectedSignature {
		t.Errorf("expected X-Amz-Signature=%s, got %s", expectedSignature, query.Get("X-Amz-Signature"))
	}

	// Should preserve existing query params
	if query.Get("existing") != "param" {
		t.Errorf("expected existing=param, got existing=%s", query.Get("existing"))
	}
}

func TestSignRequestQueryStringWithSession(t *testing.T) {
	signer := New()

	req := newRequest(nil)

	err := signer.SignRequest(&SignRequestInput{
		Request:              req,
		Credentials:          credsSession,
		Service:              "s3",
		Region:               "us-east-1",
		Time:                 time.Unix(1375315200, 0),
	SignatureType: v4.SignatureTypeQueryString,
	})

	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	query := req.URL.Query()
	if query.Get("X-Amz-SignedHeaders") != "host" {
		t.Errorf("expected X-Amz-SignedHeaders=host, got %s", query.Get("X-Amz-SignedHeaders"))
	}
	if query.Get("X-Amz-Security-Token") != "SESSION" {
		t.Errorf("expected X-Amz-Security-Token=SESSION, got %s", query.Get("X-Amz-Security-Token"))
	}
	expectedSignature := "485caefdc25560b950ea52c7cd87bc607084a43193387b1737bd2290ba3b699a"
	if query.Get("X-Amz-Signature") != expectedSignature {
		t.Errorf("expected X-Amz-Signature=%s, got %s", expectedSignature, query.Get("X-Amz-Signature"))
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
		Region:               "us-east-1",
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
func TestBackwardsCompatibility(t *testing.T) {
	signer := New()

	req := newRequest(nil)

	err := signer.SignRequest(&SignRequestInput{
		Request:     req,
		Credentials: credsNoSession,
		Service:     "s3",
		Region:      "us-east-1",
		Time:        time.Unix(1375315200, 0),
	})

	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	// Should behave like header method (old behavior)
	if auth := req.Header.Get("Authorization"); auth == "" {
		t.Error("expected Authorization header to be set for backwards compatibility")
	}

	// Should not have query parameters
	query := req.URL.Query()
	if query.Get("X-Amz-Algorithm") != "" {
		t.Error("expected no X-Amz-Algorithm in query string for backwards compatibility")
	}
}
