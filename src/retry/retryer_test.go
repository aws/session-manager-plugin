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

// retry implements back off retry strategy for reconnect web socket connection.
package retry

import (
	"errors"
	"math/rand"
	"testing"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/stretchr/testify/assert"
)

var (
	callableFunc = func() error {
		return errors.New("Error occured in callable function")
	}
)

func TestRepeatableExponentialRetryerRetriesForGivenNumberOfMaxRetries(t *testing.T) {
	retryer := RepeatableExponentialRetryer{
		callableFunc,
		config.RetryBase,
		rand.Intn(config.DataChannelRetryInitialDelayMillis) + config.DataChannelRetryInitialDelayMillis,
		config.DataChannelRetryMaxIntervalMillis,
		config.DataChannelNumMaxRetries,
	}
	err := retryer.Call()
	assert.NotNil(t, err)
}
