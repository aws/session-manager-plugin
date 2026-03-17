//go:build e2e
// +build e2e

package sigv4a

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	"github.com/aws/smithy-go/aws-http-auth/sigv4"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

type closer struct{ io.ReadSeeker }

func (closer) Close() error { return nil }

type ToXML interface {
	ToXML() []byte
}

type S3Client struct {
	Region      string
	AccountID   string
	Credentials credentials.Credentials

	HTTPClient *http.Client
	V4         *sigv4.Signer
	V4A        *Signer
}

func (c *S3Client) CreateBucket(ctx context.Context, in *CreateBucketInput) (*CreateBucketOutput, error) {
	var out CreateBucketOutput
	endpoint := fmt.Sprintf("https://%s.s3.%s.amazonaws.com", in.Bucket, c.Region)
	method := http.MethodPut
	path := "/"

	sign := signV4(c.V4, c.Credentials, c.Region)
	if err := c.do(ctx, method, endpoint, path, in, &out, sign); err != nil {
		return nil, err
	}
	return &out, nil
}

type CreateBucketInput struct {
	Bucket string
}

func (*CreateBucketInput) ToXML() []byte {
	return []byte("")
}

type CreateBucketOutput struct{}

func (c *S3Client) DeleteBucket(ctx context.Context, in *DeleteBucketInput) (*DeleteBucketOutput, error) {
	var out DeleteBucketOutput
	endpoint := fmt.Sprintf("https://%s.s3.%s.amazonaws.com", in.Bucket, c.Region)
	method := http.MethodDelete
	path := "/"

	sign := signV4(c.V4, c.Credentials, c.Region)
	if err := c.do(ctx, method, endpoint, path, in, &out, sign); err != nil {
		return nil, err
	}
	return &out, nil
}

type DeleteBucketInput struct {
	Bucket string
}

func (*DeleteBucketInput) ToXML() []byte {
	return []byte("")
}

type DeleteBucketOutput struct{}

func (c *S3Client) PutObjectMRAP(ctx context.Context, in *PutObjectMRAPInput) (*PutObjectMRAPOutput, error) {
	var out PutObjectMRAPOutput
	endpoint := fmt.Sprintf("https://%s.accesspoint.s3-global.amazonaws.com", in.MRAPAlias)
	method := http.MethodPut
	path := "/" + in.Key

	sign := signV4A(c.V4A, c.Credentials, true) // unsigned payload
	if err := c.do(ctx, method, endpoint, path, in, &out, sign); err != nil {
		return nil, err
	}
	return &out, nil
}

type PutObjectMRAPInput struct {
	MRAPAlias string
	Key       string

	ObjectData string
}

func (i *PutObjectMRAPInput) ToXML() []byte {
	// not actually XML but good enough to get the object data into the request
	// body
	return []byte(i.ObjectData)
}

type PutObjectMRAPOutput struct{}

func (c *S3Client) DeleteObjectMRAP(ctx context.Context, in *DeleteObjectMRAPInput) (*DeleteObjectMRAPOutput, error) {
	var out DeleteObjectMRAPOutput
	endpoint := fmt.Sprintf("https://%s.accesspoint.s3-global.amazonaws.com", in.MRAPAlias)
	method := http.MethodDelete
	path := "/" + in.Key

	sign := signV4A(c.V4A, c.Credentials, false)
	if err := c.do(ctx, method, endpoint, path, in, &out, sign); err != nil {
		return nil, err
	}
	return &out, nil
}

type DeleteObjectMRAPInput struct {
	MRAPAlias string
	Key       string
}

func (i *DeleteObjectMRAPInput) ToXML() []byte {
	return []byte("")
}

type DeleteObjectMRAPOutput struct{}

func signV4(signer *sigv4.Signer, creds credentials.Credentials, region string) func(*http.Request) error {
	return func(r *http.Request) error {
		return signer.SignRequest(&sigv4.SignRequestInput{
			Request:     r,
			Credentials: creds,
			Service:     "s3",
			Region:      region,
		})
	}
}

func signV4A(signer *Signer, creds credentials.Credentials, isUnsignedPayload bool) func(*http.Request) error {
	var payloadHash []byte
	if isUnsignedPayload {
		payloadHash = v4.UnsignedPayload()
	}
	return func(r *http.Request) error {
		err := signer.SignRequest(&SignRequestInput{
			Request:     r,
			PayloadHash: payloadHash,
			Credentials: creds,
			Service:     "s3",
			RegionSet:   []string{"*"},
		})

		fmt.Println("signed request ------------------------------------------")
		fmt.Printf("%s %s\n", r.Method, r.URL.EscapedPath())
		for h := range r.Header {
			fmt.Printf("%s: %s\n", h, r.Header.Get(h))
		}
		fmt.Println("---------------------------------------------------------")

		return err
	}
}

func (c *S3Client) do(ctx context.Context, method, endpoint, path string, in ToXML, out any, signRequest func(*http.Request) error) error {
	// init
	req, err := http.NewRequestWithContext(ctx, method, endpoint, http.NoBody)
	if err != nil {
		return fmt.Errorf("new http request: %w", err)
	}

	// serialize
	req.URL.Path = path
	req.Header.Set("Content-Type", "application/xml")
	payload := in.ToXML()
	req.Body = closer{bytes.NewReader(payload)}
	req.ContentLength = int64(len(payload))

	// sign
	err = signRequest(req)
	if err != nil {
		return fmt.Errorf("sign request: %w", err)
	}

	// round-trip
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("do request: %w", err)
	}
	defer resp.Body.Close()

	// deserialize
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response body: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("request error: %s: %s", resp.Status, data)
	}
	if len(data) == 0 {
		return nil
	}
	if err := xml.Unmarshal(data, out); err != nil {
		return fmt.Errorf("deserialize response: %w", err)
	}

	return nil
}

type S3ControlClient struct {
	// s3control only does us-west-2
	// Region      string
	AccountID   string
	Credentials credentials.Credentials

	HTTPClient *http.Client
	Signer     *sigv4.Signer
}

func (c *S3ControlClient) GetMRAP(ctx context.Context, in *GetMRAPInput) (*GetMRAPOutput, error) {
	var out GetMRAPOutput
	method := http.MethodGet
	path := "/v20180820/mrap/instances/" + in.Name
	if err := c.do(ctx, c.AccountID, method, path, in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type GetMRAPInput struct {
	Name string
}

func (i *GetMRAPInput) ToXML() []byte {
	return []byte("")
}

type GetMRAPOutput struct {
	AccessPoint struct {
		Alias string
	}
}

func (c *S3ControlClient) CreateMRAP(ctx context.Context, in *CreateMRAPInput) (*CreateMRAPOutput, error) {
	var out CreateMRAPOutput
	method := http.MethodPost
	path := "/v20180820/async-requests/mrap/create"
	if err := c.do(ctx, c.AccountID, method, path, in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type CreateMRAPInput struct {
	Name   string
	Bucket string
}

func (i *CreateMRAPInput) ToXML() []byte {
	const tmpl = `
<CreateMultiRegionAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
	<ClientToken>%s</ClientToken>
	<Details>
		<Name>%s</Name>
		<Regions>
			<Region>
				<Bucket>%s</Bucket>
			</Region>
		</Regions>
	</Details>
</CreateMultiRegionAccessPointRequest>`

	token := fmt.Sprintf("%d", rand.Int31())
	return []byte(fmt.Sprintf(tmpl, token, i.Name, i.Bucket))
}

type CreateMRAPOutput struct {
	RequestToken string `xml:"RequestTokenARN"`
}

func (c *S3ControlClient) DescribeMRAPOperation(ctx context.Context, in *DescribeMRAPOperationInput) (*DescribeMRAPOperationOutput, error) {
	var out DescribeMRAPOperationOutput
	method := http.MethodGet
	path := "/v20180820/async-requests/mrap/" + in.RequestToken
	if err := c.do(ctx, c.AccountID, method, path, in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type DescribeMRAPOperationInput struct {
	RequestToken string
}

func (i *DescribeMRAPOperationInput) ToXML() []byte {
	return []byte("")
}

type DescribeMRAPOperationOutput struct {
	AsyncOperation struct {
		RequestStatus string
	}
}

func (c *S3ControlClient) DeleteMRAP(ctx context.Context, in *DeleteMRAPInput) (*DeleteMRAPOutput, error) {
	var out DeleteMRAPOutput
	method := http.MethodPost
	path := "/v20180820/async-requests/mrap/delete"
	if err := c.do(ctx, c.AccountID, method, path, in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type DeleteMRAPInput struct {
	Name string
}

func (i *DeleteMRAPInput) ToXML() []byte {
	const tmpl = `
<DeleteMultiRegionAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
	<ClientToken>%s</ClientToken>
	<Details>
		<Name>%s</Name>
	</Details>
</DeleteMultiRegionAccessPointRequest>`

	token := fmt.Sprintf("%d", rand.Int31())
	return []byte(fmt.Sprintf(tmpl, token, i.Name))
}

type DeleteMRAPOutput struct {
	RequestToken string `xml:"RequestTokenARN"`
}

func (c *S3ControlClient) do(ctx context.Context, accountID, method, path string, in ToXML, out any) error {
	// init
	endpoint := fmt.Sprintf("https://%s.s3-control.us-west-2.amazonaws.com", accountID)
	req, err := http.NewRequestWithContext(ctx, method, endpoint, http.NoBody)
	if err != nil {
		return fmt.Errorf("new http request: %w", err)
	}

	// serialize
	req.URL.Path = path
	req.Header.Set("Content-Type", "application/xml")
	req.Header.Set("X-Amz-Account-Id", accountID)
	payload := in.ToXML()
	req.Body = closer{bytes.NewReader(payload)}
	req.ContentLength = int64(len(payload))

	// sign
	err = c.Signer.SignRequest(&sigv4.SignRequestInput{
		Request:     req,
		Credentials: c.Credentials,
		Service:     "s3",
		Region:      "us-west-2",
	})
	if err != nil {
		return fmt.Errorf("sign request: %w", err)
	}

	// round-trip
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("do request: %w", err)
	}
	defer resp.Body.Close()

	// deserialize
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response body: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("request error: %s: %s", resp.Status, data)
	}
	if len(data) == 0 {
		return nil
	}
	if err := xml.Unmarshal(data, out); err != nil {
		return fmt.Errorf("deserialize response: %w", err)
	}

	return nil
}

// WARNING: this test takes a while, because creating an MRAP is asynchronous
// and slow
//
// 1. creates a bucket in us-east-1
// 2. creates an MRAP that points to that bucket
// 3. polls MRAP status until created
// 4. puts object to MRAP
// 5. deletes object
// 6. deletes MRAP
// 7. deletes bucket
func TestE2E_S3MRAP(t *testing.T) {
	svc := &S3Client{
		Region:    "us-east-1",
		AccountID: os.Getenv("AWS_ACCOUNT_ID"),
		Credentials: credentials.Credentials{
			AccessKeyID:     os.Getenv("AWS_ACCESS_KEY_ID"),
			SecretAccessKey: os.Getenv("AWS_SECRET_ACCESS_KEY"),
			SessionToken:    os.Getenv("AWS_SESSION_TOKEN"),
		},
		HTTPClient: http.DefaultClient,
		V4: sigv4.New(func(o *v4.SignerOptions) {
			o.DisableDoublePathEscape = true
			o.AddPayloadHashHeader = true
		}),
		V4A: New(func(o *v4.SignerOptions) {
			o.DisableDoublePathEscape = true
			o.AddPayloadHashHeader = true
		}),
	}
	controlsvc := &S3ControlClient{
		AccountID: os.Getenv("AWS_ACCOUNT_ID"),
		Credentials: credentials.Credentials{
			AccessKeyID:     os.Getenv("AWS_ACCESS_KEY_ID"),
			SecretAccessKey: os.Getenv("AWS_SECRET_ACCESS_KEY"),
			SessionToken:    os.Getenv("AWS_SESSION_TOKEN"),
		},
		HTTPClient: http.DefaultClient,
		Signer: sigv4.New(func(o *v4.SignerOptions) {
			o.AddPayloadHashHeader = true
		}),
	}

	testid := rand.Int() % (2 << 15)
	bucket := fmt.Sprintf("aws-http-auth-e2etest-bucket-%d", testid)
	mrap := fmt.Sprintf("aws-http-auth-e2etest-mrap-%d", testid)

	_, err := svc.CreateBucket(context.Background(), &CreateBucketInput{
		Bucket: bucket,
	})
	if err != nil {
		t.Fatalf("create bucket: %v", err)
	}

	t.Logf("created test bucket: %s", bucket)

	createMRAPOutput, err := controlsvc.CreateMRAP(context.Background(), &CreateMRAPInput{
		Name:   mrap,
		Bucket: bucket,
	})
	if err != nil {
		t.Fatalf("create mrap: %v", err)
	}
	t.Logf("started mrap create... token %s", createMRAPOutput.RequestToken)
	awaitS3ControlOperation(t, context.Background(), controlsvc, createMRAPOutput.RequestToken)

	t.Logf("created test mrap: %s", mrap)

	getMRAPOutput, err := controlsvc.GetMRAP(context.Background(), &GetMRAPInput{
		Name: mrap,
	})
	if err != nil {
		t.Fatalf("get mrap info: %v", err)
	}

	t.Logf("retrieved mrap alias: %s", getMRAPOutput.AccessPoint.Alias)

	_, err = svc.PutObjectMRAP(context.Background(), &PutObjectMRAPInput{
		MRAPAlias:  getMRAPOutput.AccessPoint.Alias,
		Key:        "path1 / path2", // verify single-encode behavior
		ObjectData: mrap,
	})
	if err != nil {
		t.Fatalf("put object mrap: %v", err)
	}

	_, err = svc.DeleteObjectMRAP(context.Background(), &DeleteObjectMRAPInput{
		MRAPAlias: getMRAPOutput.AccessPoint.Alias,
		Key:       "path1 / path2",
	})
	if err != nil {
		t.Fatalf("delete object mrap: %v", err)
	}

	deleteMRAPOutput, err := controlsvc.DeleteMRAP(context.Background(), &DeleteMRAPInput{
		Name: mrap,
	})
	if err != nil {
		t.Fatalf("delete mrap: %v", err)
	}
	t.Logf("started mrap delete... token %s", deleteMRAPOutput.RequestToken)
	awaitS3ControlOperation(t, context.Background(), controlsvc, deleteMRAPOutput.RequestToken)

	_, err = svc.DeleteBucket(context.Background(), &DeleteBucketInput{
		Bucket: bucket,
	})
	if err != nil {
		t.Fatalf("delete bucket: %v", err)
	}

	t.Logf("deleted test bucket: %s", bucket)
}

func awaitS3ControlOperation(t *testing.T, ctx context.Context, svc *S3ControlClient, requestToken string) {
	t.Helper()

	start := time.Now()
	for {
		out, err := svc.DescribeMRAPOperation(ctx, &DescribeMRAPOperationInput{
			RequestToken: requestToken,
		})
		if err != nil {
			t.Fatalf("describe mrap operation: %v", err)
		}

		t.Logf("poll status: %s\n", out.AsyncOperation.RequestStatus)
		time.Sleep(5 * time.Second)

		// S3Control does not document the values for this field.
		// Anecdotally:
		//   - returns NEW a few seconds after the operation starts
		//   - returns INPROGRESS until complete
		//   - returns SUCCEEDED when complete
		if out.AsyncOperation.RequestStatus == "SUCCEEDED" {
			break
		}
	}
	t.Logf("operation completed after %v", time.Now().Sub(start))
}
