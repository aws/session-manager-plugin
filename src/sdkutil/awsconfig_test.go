// Copyright 2026 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package sdkutil

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestSetRegionAndProfile(t *testing.T) {
	SetRegionAndProfile("us-west-2", "test-profile")
	assert.Equal(t, "us-west-2", defaultRegion)
	assert.Equal(t, "test-profile", defaultProfile)

	// Clean up
	SetRegionAndProfile("", "")
}

func TestGetRegion(t *testing.T) {
	SetRegionAndProfile("eu-west-1", "")
	assert.Equal(t, "eu-west-1", GetRegion())

	// Clean up
	SetRegionAndProfile("", "")
}

func TestGetDefaultConfigReturnsConfigWithoutEndpoint(t *testing.T) {
	SetRegionAndProfile("us-east-1", "")
	defer SetRegionAndProfile("", "")

	ctx := context.Background()
	cfg, err := GetDefaultConfig(ctx)

	// Config should be created successfully (credentials may fail in test env, that's ok)
	// The key assertion is that no global endpoint resolver is set
	assert.NoError(t, err)
	assert.Equal(t, "us-east-1", cfg.Region)
	// EndpointResolverWithOptions should be nil — no global endpoint override
	assert.Nil(t, cfg.EndpointResolverWithOptions)
}

func TestGetConfigWithQuickCheckReturnsConfigWithoutEndpoint(t *testing.T) {
	SetRegionAndProfile("ap-southeast-1", "")
	defer SetRegionAndProfile("", "")

	ctx := context.Background()
	cfg, err := GetConfigWithQuickCheck(ctx)

	assert.NoError(t, err)
	assert.Equal(t, "ap-southeast-1", cfg.Region)
	// EndpointResolverWithOptions should be nil — no global endpoint override
	assert.Nil(t, cfg.EndpointResolverWithOptions)
}

func TestGetDefaultConfigWithProfile(t *testing.T) {
	SetRegionAndProfile("us-east-1", "my-profile")
	defer SetRegionAndProfile("", "")

	ctx := context.Background()
	cfg, err := GetDefaultConfig(ctx)
	// A non-existent profile will cause LoadDefaultConfig to error,
	// which is expected behavior — the important thing is that the
	// function propagates the error correctly.
	if err != nil {
		assert.Contains(t, err.Error(), "my-profile")
		return
	}
	// If the profile happens to exist in the test env, verify region is set
	assert.Equal(t, "us-east-1", cfg.Region)
}

func TestGetDefaultConfigWithEmptyRegion(t *testing.T) {
	t.Setenv("AWS_REGION", "")
	t.Setenv("AWS_DEFAULT_REGION", "")
	t.Setenv("AWS_CONFIG_FILE", "nonexistent")

	SetRegionAndProfile("", "")

	ctx := context.Background()
	cfg, err := GetDefaultConfig(ctx)
	assert.NoError(t, err)
	assert.Empty(t, cfg.Region)
}
