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
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
)

var defaultRegion string
var defaultProfile string

// GetDefaultConfig creates aws sdk config with given profile and region
func GetDefaultConfig(ctx context.Context) (aws.Config, error) {
	// Build config options
	opts := []func(*config.LoadOptions) error{
		config.WithRegion(defaultRegion),
		config.WithRetryMaxAttempts(3),
	}

	// Add profile if specified
	if defaultProfile != "" {
		opts = append(opts, config.WithSharedConfigProfile(defaultProfile))
	}

	// Load config
	cfg, err := config.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return aws.Config{}, fmt.Errorf("error creating new aws sdk config: %w", err)
	}

	return cfg, nil
}

// GetConfigWithQuickCheck creates aws sdk config with minimal retry logic
// to avoid delays while maintaining reliability (used for credential checks)
func GetConfigWithQuickCheck(ctx context.Context) (aws.Config, error) {
	// Build config options with minimal retries
	opts := []func(*config.LoadOptions) error{
		config.WithRegion(defaultRegion),
		config.WithRetryMaxAttempts(1),
	}

	// Add profile if specified
	if defaultProfile != "" {
		opts = append(opts, config.WithSharedConfigProfile(defaultProfile))
	}

	// Load config
	cfg, err := config.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return aws.Config{}, fmt.Errorf("error creating new aws sdk config: %w", err)
	}

	return cfg, nil
}

// SetRegionAndProfile sets the region and profile for default aws configs
func SetRegionAndProfile(region string, profile string) {
	defaultRegion = region
	defaultProfile = profile
}

// GetRegion gets the region for default aws configs
func GetRegion() string {
	return defaultRegion
}
