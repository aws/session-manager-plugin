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

// message package defines data channel messages structure.
package message

import (
	"encoding/json"
	"time"
)

// ActionType used in Handshake to determine action requested by the agent
type ActionType string

const (
	KMSEncryption ActionType = "KMSEncryption"
	SessionType   ActionType = "SessionType"
)

type ActionStatus int

const (
	Success     ActionStatus = 1
	Failed      ActionStatus = 2
	Unsupported ActionStatus = 3
)

// This is sent by the agent to initialize KMS encryption
type KMSEncryptionRequest struct {
	KMSKeyID string `json:"KMSKeyId"`
}

// This is received by the agent to set up KMS encryption
type KMSEncryptionResponse struct {
	KMSCipherTextKey  []byte `json:"KMSCipherTextKey"`
	KMSCipherTextHash []byte `json:"KMSCipherTextHash"`
}

// SessionType request contains type of the session that needs to be launched and properties for plugin
type SessionTypeRequest struct {
	SessionType string      `json:"SessionType"`
	Properties  interface{} `json:"Properties"`
}

// Handshake payload sent by the agent to the session manager plugin
type HandshakeRequestPayload struct {
	AgentVersion           string                  `json:"AgentVersion"`
	RequestedClientActions []RequestedClientAction `json:"RequestedClientActions"`
}

// An action requested by the agent to the plugin
type RequestedClientAction struct {
	ActionType       ActionType      `json:"ActionType"`
	ActionParameters json.RawMessage `json:"ActionParameters"`
}

// The result of processing the action by the plugin
type ProcessedClientAction struct {
	ActionType   ActionType   `json:"ActionType"`
	ActionStatus ActionStatus `json:"ActionStatus"`
	ActionResult interface{}  `json:"ActionResult"`
	Error        string       `json:"Error"`
}

// Handshake Response sent by the plugin in response to the handshake request
type HandshakeResponsePayload struct {
	ClientVersion          string                  `json:"ClientVersion"`
	ProcessedClientActions []ProcessedClientAction `json:"ProcessedClientActions"`
	Errors                 []string                `json:"Errors"`
}

// This is sent by the agent as a challenge to the client. The challenge field
// is some data that was encrypted by the agent. The client must be able to decrypt
// this and in turn encrypt it with its own key.
type EncryptionChallengeRequest struct {
	Challenge []byte `json:"Challenge"`
}

// This is received by the agent from the client. The challenge field contains
// some data received, decrypted and then encrypted by the client. Agent must
// be able to decrypt this and verify it matches the original plaintext challenge.
type EncryptionChallengeResponse struct {
	Challenge []byte `json:"Challenge"`
}

// Handshake Complete indicates to client that handshake is complete.
// This signals the client to start the plugin and display a customer message where appropriate.
type HandshakeCompletePayload struct {
	HandshakeTimeToComplete time.Duration `json:"HandshakeTimeToComplete"`
	CustomerMessage         string        `json:"CustomerMessage"`
}
