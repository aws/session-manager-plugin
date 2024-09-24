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

// Package service is a wrapper for the new Service
package service

// OpenDataChannelInput
type OpenDataChannelInput struct {
	_ struct{} `type:"structure"`

	// MessageSchemaVersion is a required field
	MessageSchemaVersion *string `json:"MessageSchemaVersion" min:"1" type:"string" required:"true"`

	// RequestId is a required field
	RequestId *string `json:"RequestId" min:"16" type:"string" required:"true"`

	// TokenValue is a required field
	TokenValue *string `json:"TokenValue" min:"1" type:"string" required:"true"`

	// ClientId is a required field
	ClientId *string `json:"ClientId" min:"1" type:"string" required:"true"`

	// ClientVersion is a required field
	ClientVersion *string `json:"ClientVersion" min:"1" type:"string" required:"true"`
}
