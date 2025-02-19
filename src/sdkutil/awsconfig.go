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

// Package sdkutil provides utilities used to call awssdk.
package sdkutil

import (
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/session-manager-plugin/src/sdkutil/retryer"
)

var defaultRegion string
var defaultProfile string

// GetNewSessionWithEndpoint creates aws sdk session with given profile, region and endpoint
func GetNewSessionWithEndpoint(endpoint string) (sess *session.Session, err error) {
	if sess, err = session.NewSessionWithOptions(session.Options{
		Config: aws.Config{
			Retryer:    newRetryer(),
			SleepDelay: sleepDelay,
			Region:     aws.String(defaultRegion),
			Endpoint:   aws.String(endpoint),
		},
		SharedConfigState: session.SharedConfigEnable,
		Profile:           defaultProfile,
	}); err != nil {
		return nil, fmt.Errorf("Error creating new aws sdk session %s", err)
	}
	return sess, nil
}

// GetSessionWithQuickCheck creates aws sdk session with fast checking
// with minimal retry logic to avoid delays while maintaining reliability
func GetSessionWithQuickCheck(endpoint string) (sess *session.Session, err error) {
	if sess, err = session.NewSessionWithOptions(session.Options{
		Config: aws.Config{
			Region:     aws.String(defaultRegion),
			Endpoint:   aws.String(endpoint),
			MaxRetries: aws.Int(1),
			SleepDelay: fastSleepDelay,
		},
		SharedConfigState: session.SharedConfigEnable,
		Profile:           defaultProfile,
	}); err != nil {

		return nil, fmt.Errorf("Error creating new aws sdk session %s", err)
	}
	return sess, nil
}

// GetDefaultSession creates aws sdk session with given profile and region
func GetDefaultSession() (sess *session.Session, err error) {
	return GetNewSessionWithEndpoint("")
}

// Sets the region and profile for default aws sessions
func SetRegionAndProfile(region string, profile string) {
	defaultRegion = region
	defaultProfile = profile
}

// Gets the region for default aws sessions
func GetRegion() (region string) {
	return defaultRegion
}

var newRetryer = func() aws.RequestRetryer {
	r := retryer.SsmCliRetryer{}
	r.NumMaxRetries = 3
	return r
}

var sleepDelay = func(d time.Duration) {
	time.Sleep(d)
}

var fastSleepDelay = func(d time.Duration) {
	// For credential checks, use minimal delay (max 50ms)
	if d > 50*time.Millisecond {
		time.Sleep(50 * time.Millisecond)
	} else {
		time.Sleep(d)
	}
}
