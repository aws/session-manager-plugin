package v4

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

// Tests herein are meant to verify individual components of the v4 signer
// implementation and should generally not be calling Do() directly.
//
// The full algorithm contained in Do() is covered by tests for the
// Sigv4/Sigv4a APIs.

func seekable(v string) io.ReadSeekCloser {
	return readseekcloser{strings.NewReader(v)}
}

type readseekcloser struct {
	io.ReadSeeker
}

func (readseekcloser) Close() error { return nil }

type identityFinalizer struct{}

func (identityFinalizer) SignString(v string) (string, error) {
	return v, nil
}

func TestQueryStringAuth_IncludesSignedHeadersInCanonicalRequest(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, "https://service.region.amazonaws.com/path", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.URL.RawQuery = "existing=param"

	s := &Signer{
		Request:              req,
		PayloadHash:          []byte("UNSIGNED-PAYLOAD"),
		Time:                 time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC),
		Credentials:          credentials.Credentials{AccessKeyID: "AKID", SecretAccessKey: "SECRET"},
		Algorithm:            "AWS4-HMAC-SHA256",
		CredentialScope:      "20240101/us-east-1/service/aws4_request",
		Finalizer:            identityFinalizer{},
		SignatureType: v4.SignatureTypeQueryString,
	}

	err = s.Do()
	if err != nil {
		t.Fatal(err)
	}

	query := req.URL.Query()
	if !query.Has("X-Amz-SignedHeaders") {
		t.Error("X-Amz-SignedHeaders missing from query string")
	}
	if !query.Has("X-Amz-Algorithm") {
		t.Error("X-Amz-Algorithm missing from query string")
	}
	if !query.Has("X-Amz-Credential") {
		t.Error("X-Amz-Credential missing from query string")
	}
	if !query.Has("X-Amz-Date") {
		t.Error("X-Amz-Date missing from query string")
	}
	if !query.Has("X-Amz-Signature") {
		t.Error("X-Amz-Signature missing from query string")
	}
	if !query.Has("existing") {
		t.Error("existing query parameter should be preserved")
	}
}

func TestQueryStringAuth_WithSessionToken(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, "https://service.region.amazonaws.com/path", nil)
	if err != nil {
		t.Fatal(err)
	}

	s := &Signer{
		Request:              req,
		PayloadHash:          []byte("UNSIGNED-PAYLOAD"),
		Time:                 time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC),
		Credentials:          credentials.Credentials{AccessKeyID: "AKID", SecretAccessKey: "SECRET", SessionToken: "TOKEN123"},
		Algorithm:            "AWS4-HMAC-SHA256",
		CredentialScope:      "20240101/us-east-1/service/aws4_request",
		Finalizer:            identityFinalizer{},
		SignatureType: v4.SignatureTypeQueryString,
	}

	err = s.Do()
	if err != nil {
		t.Fatal(err)
	}

	query := req.URL.Query()
	if !query.Has("X-Amz-Security-Token") {
		t.Error("X-Amz-Security-Token missing from query string")
	}
	if query.Get("X-Amz-Security-Token") != "TOKEN123" {
		t.Errorf("X-Amz-Security-Token = %q, want %q", query.Get("X-Amz-Security-Token"), "TOKEN123")
	}
}

func TestQueryStringAuth_WithRegionSet(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, "https://service.region.amazonaws.com/path", nil)
	if err != nil {
		t.Fatal(err)
	}

	// Pre-populate X-Amz-Region-Set as query parameter (simulating SigV4a)
	query := req.URL.Query()
	query.Set("X-Amz-Region-Set", "us-east-1,us-west-2")
	req.URL.RawQuery = query.Encode()

	s := &Signer{
		Request:              req,
		PayloadHash:          []byte("UNSIGNED-PAYLOAD"),
		Time:                 time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC),
		Credentials:          credentials.Credentials{AccessKeyID: "AKID", SecretAccessKey: "SECRET"},
		Algorithm:            "AWS4-ECDSA-P256-SHA256",
		CredentialScope:      "20240101/service/aws4_request",
		Finalizer:            identityFinalizer{},
		SignatureType: v4.SignatureTypeQueryString,
	}

	err = s.Do()
	if err != nil {
		t.Fatal(err)
	}

	finalQuery := req.URL.Query()
	if !finalQuery.Has("X-Amz-Region-Set") {
		t.Error("X-Amz-Region-Set missing from final query string")
	}
	if finalQuery.Get("X-Amz-Region-Set") != "us-east-1,us-west-2" {
		t.Errorf("X-Amz-Region-Set = %q, want %q", finalQuery.Get("X-Amz-Region-Set"), "us-east-1,us-west-2")
	}
}

func TestBuildCanonicalRequest_SignedPayload(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/path1/path 2"
	req.URL.RawQuery = "a=b"
	req.Header.Set("Host", "service.region.amazonaws.com")
	req.Header.Set("X-Amz-Foo", "\t \tbar ")
	s := &Signer{
		Request:     req,
		PayloadHash: Stosha("{}"),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/path1/path%25202
a=b
host:service.region.amazonaws.com
x-amz-foo:bar

host;x-amz-foo
44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_NoPath(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = ""
	req.URL.RawQuery = "a=b"
	req.Header.Set("Host", "service.region.amazonaws.com")
	req.Header.Set("X-Amz-Foo", "\t \tbar ")
	s := &Signer{
		Request:     req,
		PayloadHash: Stosha("{}"),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/
a=b
host:service.region.amazonaws.com
x-amz-foo:bar

host;x-amz-foo
44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_DoubleHeader(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/"
	req.Header.Set("X-Amz-Foo", "\t \tbar ")
	req.Header.Set("Host", "service.region.amazonaws.com")
	req.Header.Set("dontsignit", "dontsignit") // should be skipped
	req.Header.Add("X-Amz-Foo", "\t \tbaz ")
	s := &Signer{
		Request:     req,
		PayloadHash: Stosha("{}"),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/

host:service.region.amazonaws.com
x-amz-foo:bar,baz

host;x-amz-foo
44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_SortQuery(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/"
	req.URL.RawQuery = "a=b&%20b=c"
	req.Header.Set("Host", "service.region.amazonaws.com")
	s := &Signer{
		Request:     req,
		PayloadHash: v4.UnsignedPayload(),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/
%20b=c&a=b
host:service.region.amazonaws.com

host
UNSIGNED-PAYLOAD`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_EmptyQuery(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/"
	req.URL.RawQuery = "foo"
	req.Header.Set("Host", "service.region.amazonaws.com")
	s := &Signer{
		Request:     req,
		PayloadHash: v4.UnsignedPayload(),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/
foo=
host:service.region.amazonaws.com

host
UNSIGNED-PAYLOAD`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_UnsignedPayload(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/path1/path 2"
	req.URL.RawQuery = "a=b"
	req.Header.Set("Host", "service.region.amazonaws.com")
	req.Header.Set("X-Amz-Foo", "\t \tbar ")
	s := &Signer{
		Request:     req,
		PayloadHash: []byte("UNSIGNED-PAYLOAD"),
		Options: v4.SignerOptions{
			HeaderRules: defaultHeaderRules{},
		},
	}

	expect := `POST
/path1/path%25202
a=b
host:service.region.amazonaws.com
x-amz-foo:bar

host;x-amz-foo
UNSIGNED-PAYLOAD`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestBuildCanonicalRequest_DisableDoubleEscape(t *testing.T) {
	req, err := http.NewRequest(http.MethodPost,
		"https://service.region.amazonaws.com",
		seekable("{}"))
	if err != nil {
		t.Fatal(err)
	}

	req.URL.Path = "/path1/path 2"
	req.URL.RawQuery = "a=b"
	req.Header.Set("Host", "service.region.amazonaws.com")
	req.Header.Set("X-Amz-Foo", "\t \tbar ")
	s := &Signer{
		Request:     req,
		PayloadHash: []byte("UNSIGNED-PAYLOAD"),
		Options: v4.SignerOptions{
			HeaderRules:             defaultHeaderRules{},
			DisableDoublePathEscape: true,
		},
	}

	expect := `POST
/path1/path%202
a=b
host:service.region.amazonaws.com
x-amz-foo:bar

host;x-amz-foo
UNSIGNED-PAYLOAD`

	actual, _ := s.buildCanonicalRequest()
	if expect != actual {
		t.Errorf("canonical request\n%s\n!=\n%s", expect, actual)
	}
}

func TestResolvePayloadHash_AlreadySet(t *testing.T) {
	expect := "already set"
	s := &Signer{
		PayloadHash: []byte(expect),
	}

	err := s.resolvePayloadHash()
	if err != nil {
		t.Fatalf("expect no err, got %v", err)
	}

	if expect != string(s.PayloadHash) {
		t.Fatalf("hash %q != %q", expect, s.PayloadHash)
	}
}

func TestResolvePayloadHash_Disabled(t *testing.T) {
	expect := "UNSIGNED-PAYLOAD"
	s := &Signer{
		Request: &http.Request{Body: seekable("foo")},
		Options: v4.SignerOptions{
			DisableImplicitPayloadHashing: true,
		},
	}

	err := s.resolvePayloadHash()
	if err != nil {
		t.Fatalf("expect no err, got %v", err)
	}

	if expect != string(s.PayloadHash) {
		t.Fatalf("hash %q != %q", expect, s.PayloadHash)
	}
}

type seekexploder struct {
	io.ReadCloser
}

func (seekexploder) Seek(int64, int) (int64, error) {
	return 0, fmt.Errorf("boom")
}

func TestResolvePayloadHash_SeekBlowsUp(t *testing.T) {
	s := &Signer{
		Request: &http.Request{
			Body: seekexploder{seekable("foo")},
		},
	}

	err := s.resolvePayloadHash()
	if err == nil {
		t.Fatalf("expect err, got none")
	}
}

func TestResolvePayloadHash_OK(t *testing.T) {
	expect := string(Stosha("foo"))
	s := &Signer{
		Request: &http.Request{Body: seekable("foo")},
	}

	err := s.resolvePayloadHash()
	if err != nil {
		t.Fatalf("expect no err, got %v", err)
	}

	if expect != string(s.PayloadHash) {
		t.Fatalf("hash %q != %q", expect, s.PayloadHash)
	}
}

func TestSetRequiredHeaders_All(t *testing.T) {
	s := &Signer{
		Request: &http.Request{
			Host:   "foo.service.com",
			Header: http.Header{},
		},
		PayloadHash: []byte{0, 1, 2, 3},
		Time:        time.Unix(0, 0).UTC(),
		Credentials: credentials.Credentials{
			SessionToken: "session_token",
		},
		Options: v4.SignerOptions{
			AddPayloadHashHeader: true,
		},
	}

	s.setRequiredHeaders()
	if actual := s.Request.Header.Get("Host"); s.Request.Host != actual {
		t.Errorf("region header %q != %q", s.Request.Host, actual)
	}
	if expect, actual := "19700101T000000Z", s.Request.Header.Get("X-Amz-Date"); expect != actual {
		t.Errorf("date header %q != %q", expect, actual)
	}
	if expect, actual := "session_token", s.Request.Header.Get("X-Amz-Security-Token"); expect != actual {
		t.Errorf("token header %q != %q", expect, actual)
	}
	if expect, actual := "00010203", s.Request.Header.Get("X-Amz-Content-Sha256"); expect != actual {
		t.Errorf("sha256 header %q != %q", expect, actual)
	}
}

func TestSetRequiredHeaders_UnsignedPayload(t *testing.T) {
	s := &Signer{
		Request: &http.Request{
			Host:   "foo.service.com",
			Header: http.Header{},
		},
		PayloadHash: []byte("UNSIGNED-PAYLOAD"),
		Time:        time.Unix(0, 0).UTC(),
		Credentials: credentials.Credentials{},
		Options: v4.SignerOptions{
			AddPayloadHashHeader: true,
		},
	}

	s.setRequiredHeaders()
	if actual := s.Request.Header.Get("Host"); s.Request.Host != actual {
		t.Errorf("region header %q != %q", s.Request.Host, actual)
	}
	if expect, actual := "19700101T000000Z", s.Request.Header.Get("X-Amz-Date"); expect != actual {
		t.Errorf("date header %q != %q", expect, actual)
	}
	if expect, actual := "", s.Request.Header.Get("X-Amz-Security-Token"); expect != actual {
		t.Errorf("token header %q != %q", expect, actual)
	}
	if expect, actual := "UNSIGNED-PAYLOAD", s.Request.Header.Get("X-Amz-Content-Sha256"); expect != actual {
		t.Errorf("sha256 header %q != %q", expect, actual)
	}
}
