// Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"). You may not
// use this file except in compliance with the License. A copy of the
// License is located at
//
// http://aws.amazon.com/apache2.0/
//
// or in the "license" file accompanying this file. This file is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
// either express or implied. See the License for the specific language governing
// permissions and limitations under the License.

// Package retryer provides custom retry logic for AWS SDK operations
// Note: This is currently unused in v2 code but kept for potential future use
package retryer

import (
	"context"
	"math"
	"math/rand"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/retry"
)

// SsmCliRetryer implements custom retry logic for SSM CLI operations
type SsmCliRetryer struct {
	*retry.Standard
}

// NewSsmCliRetryer creates a new instance of SsmCliRetryer
func NewSsmCliRetryer() *SsmCliRetryer {
	return &SsmCliRetryer{
		Standard: retry.NewStandard(func(o *retry.StandardOptions) {
			o.MaxAttempts = 3
		}),
	}
}

// MaxAttempts returns the maximum number of retry attempts
func (s *SsmCliRetryer) MaxAttempts() int {
	return s.Standard.MaxAttempts()
}

// RetryDelay returns the delay duration before retrying this request again
func (s *SsmCliRetryer) RetryDelay(attempt int, err error) (time.Duration, error) {
	// Handle GetMessages Client.Timeout error
	if err != nil && strings.Contains(err.Error(), "Client.Timeout") {
		// Expected error. We will retry with a short 100 ms delay
		return time.Duration(100 * time.Millisecond), nil
	}

	// Retry after a > 1 sec timeout, increasing exponentially with each retry
	rand.Seed(time.Now().UnixNano())
	delay := int(math.Pow(2, float64(attempt))) * (rand.Intn(500) + 1000)
	return time.Duration(delay) * time.Millisecond, nil
}

// IsErrorRetryable determines if an error should be retried
func (s *SsmCliRetryer) IsErrorRetryable(err error) bool {
	return s.Standard.IsErrorRetryable(err)
}

// GetRetryToken attempts to get a retry token
func (s *SsmCliRetryer) GetRetryToken(ctx context.Context, err error) (func(error) error, error) {
	return s.Standard.GetRetryToken(ctx, err)
}

// GetInitialToken returns the initial retry token
func (s *SsmCliRetryer) GetInitialToken() func(error) error {
	return s.Standard.GetInitialToken()
}

// Ensure SsmCliRetryer implements aws.Retryer interface
var _ aws.Retryer = (*SsmCliRetryer)(nil)
