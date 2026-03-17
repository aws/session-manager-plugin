//go:build e2e
// +build e2e

package sigv4

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"testing"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
)

type closer struct{ io.ReadSeeker }

func (closer) Close() error { return nil }

type SQSClient struct {
	Region      string
	Credentials credentials.Credentials

	HTTPClient *http.Client
	Signer     *Signer
}

// all of these method definitions are very repetitive, it would be useful if
// there was some sort of API model we could generate code from...
func (c *SQSClient) CreateQueue(ctx context.Context, in *CreateQueueInput) (*CreateQueueOutput, error) {
	var out CreateQueueOutput
	if err := c.do(ctx, "CreateQueue", in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type CreateQueueInput struct {
	QueueName string `json:"QueueName,omitempty"` // This member is required.

	Attributes map[string]string `json:"Attributes,omitempty"`
	Tags       map[string]string `json:"Tags,omitempty"`
}

type CreateQueueOutput struct {
	QueueURL string `json:"QueueUrl"`
}

func (c *SQSClient) DeleteQueue(ctx context.Context, in *DeleteQueueInput) (*DeleteQueueOutput, error) {
	var out DeleteQueueOutput
	if err := c.do(ctx, "DeleteQueue", in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type DeleteQueueInput struct {
	QueueURL string `json:"QueueUrl,omitempty"` // This member is required.
}

type DeleteQueueOutput struct{}

func (c *SQSClient) do(ctx context.Context, target string, in, out any) error {
	// init (featuring budget resolve endpoint)
	endpt := fmt.Sprintf("https://sqs.%s.amazonaws.com", c.Region)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpt, http.NoBody)
	if err != nil {
		return fmt.Errorf("new http request: %w", err)
	}

	// serialize
	req.URL.Path = "/"
	req.Header.Set("X-Amz-Target", fmt.Sprintf("AmazonSQS.%s", target))
	req.Header.Set("Content-Type", "application/x-amz-json-1.0")
	payload, err := json.Marshal(in)
	if err != nil {
		return fmt.Errorf("serialize request: %w", err)
	}
	req.Body = closer{bytes.NewReader(payload)}
	req.ContentLength = int64(len(payload))

	// sign
	err = c.Signer.SignRequest(&SignRequestInput{
		Request:     req,
		Credentials: c.Credentials,
		Service:     "sqs",
		Region:      c.Region,
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
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("deserialize response: %w", err)
	}

	return nil
}

func TestE2E_SQS(t *testing.T) {
	svc := &SQSClient{
		Region: "us-east-1",
		Credentials: credentials.Credentials{
			AccessKeyID:     os.Getenv("AWS_ACCESS_KEY_ID"),
			SecretAccessKey: os.Getenv("AWS_SECRET_ACCESS_KEY"),
			SessionToken:    os.Getenv("AWS_SESSION_TOKEN"),
		},
		HTTPClient: http.DefaultClient,
		Signer:     New(),
	}

	queueName := fmt.Sprintf("aws-http-auth-e2etest-%d", rand.Int()%(2<<15))

	out, err := svc.CreateQueue(context.Background(), &CreateQueueInput{
		QueueName: queueName,
	})
	if err != nil {
		t.Fatalf("create queue: %v", err)
	}

	queueURL := out.QueueURL
	t.Logf("created test queue %s", queueURL)

	_, err = svc.DeleteQueue(context.Background(), &DeleteQueueInput{
		QueueURL: queueURL,
	})
	if err != nil {
		t.Fatalf("delete queue: %v", err)
	}

	t.Log("deleted test queue")
}
