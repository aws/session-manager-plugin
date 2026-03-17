// Package v4 exposes common APIs for AWS Signature Version 4.
package v4

// SignatureType specifies how the signature is transmitted.
type SignatureType int

const (
	// SignatureTypeHeader transmits signature via Authorization header (default).
	SignatureTypeHeader SignatureType = iota
	// SignatureTypeQueryString transmits signature via query parameters.
	// See https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
	SignatureTypeQueryString
)

// SignerOption applies configuration to a signer.
type SignerOption func(*SignerOptions)

// SignerOptions configures SigV4.
type SignerOptions struct {
	// Rules to determine what headers are signed.
	//
	// By default, the signer will only include the minimum required headers:
	//   - Host
	//   - X-Amz-*
	HeaderRules SignedHeaderRules

	// Setting this flag will instead cause the signer to use the
	// UNSIGNED-PAYLOAD sentinel if a hash is not explicitly provided.
	DisableImplicitPayloadHashing bool

	// Disables the automatic escaping of the URI path of the request for the
	// siganture's canonical string's path.
	//
	// Amazon S3 is an example of a service that requires this setting.
	DisableDoublePathEscape bool

	// Adds the X-Amz-Content-Sha256 header to signed requests.
	//
	// Amazon S3 is an example of a service that requires this setting.
	AddPayloadHashHeader bool
}

// SignedHeaderRules determines whether a request header should be included in
// the calculated signature.
//
// By convention, ShouldSign is invoked with lowercase values.
type SignedHeaderRules interface {
	IsSigned(string) bool
}

// UnsignedPayload provides the sentinel value for a payload hash to indicate
// that a request's payload is unsigned.
func UnsignedPayload() []byte {
	return []byte("UNSIGNED-PAYLOAD")
}
