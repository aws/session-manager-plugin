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
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/twinj/uuid"
)

const (
	// InputStreamMessage represents message type for input data
	InputStreamMessage = "input_stream_data"

	// OutputStreamMessage represents message type for output data
	OutputStreamMessage = "output_stream_data"

	// AcknowledgeMessage represents message type for acknowledge
	AcknowledgeMessage = "acknowledge"

	// ChannelClosedMessage represents message type for ChannelClosed
	ChannelClosedMessage = "channel_closed"

	// StartPublicationMessage represents the message type that notifies the CLI to start sending stream messages
	StartPublicationMessage = "start_publication"

	// PausePublicationMessage represents the message type that notifies the CLI to pause sending stream messages
	// as the remote data channel is inactive
	PausePublicationMessage = "pause_publication"
)

// AcknowledgeContent is used to inform the sender of an acknowledge message that the message has been received.
// * MessageType is a 32 byte UTF-8 string containing the message type.
// * MessageId is a 40 byte UTF-8 string containing the UUID identifying this message being acknowledged.
// * SequenceNumber is an 8 byte integer containing the message sequence number for serialized message.
// * IsSequentialMessage is a boolean field representing whether the acknowledged message is part of a sequence
type AcknowledgeContent struct {
	MessageType         string `json:"AcknowledgedMessageType"`
	MessageId           string `json:"AcknowledgedMessageId"`
	SequenceNumber      int64  `json:"AcknowledgedMessageSequenceNumber"`
	IsSequentialMessage bool   `json:"IsSequentialMessage"`
}

// ChannelClosed is used to inform the client to close the channel
// * MessageId is a 40 byte UTF-8 string containing the UUID identifying this message.
// * CreatedDate is a string field containing the message create epoch millis in UTC.
// * DestinationId is a string field containing the session target.
// * SessionId is a string field representing which session to close.
// * MessageType is a 32 byte UTF-8 string containing the message type.
// * SchemaVersion is a 4 byte integer containing the message schema version number.
// * Output is a string field containing the error message for channel close.
type ChannelClosed struct {
	MessageId     string `json:"MessageId"`
	CreatedDate   string `json:"CreatedDate"`
	DestinationId string `json:"DestinationId"`
	SessionId     string `json:"SessionId"`
	MessageType   string `json:"MessageType"`
	SchemaVersion int    `json:"SchemaVersion"`
	Output        string `json:"Output"`
}

type PayloadType uint32

const (
	Output                       PayloadType = 1
	Error                        PayloadType = 2
	Size                         PayloadType = 3
	Parameter                    PayloadType = 4
	HandshakeRequestPayloadType  PayloadType = 5
	HandshakeResponsePayloadType PayloadType = 6
	HandshakeCompletePayloadType PayloadType = 7
	EncChallengeRequest          PayloadType = 8
	EncChallengeResponse         PayloadType = 9
	Flag                         PayloadType = 10
	StdErr                       PayloadType = 11
	ExitCode                     PayloadType = 12
)

type PayloadTypeFlag uint32

const (
	DisconnectToPort   PayloadTypeFlag = 1
	TerminateSession   PayloadTypeFlag = 2
	ConnectToPortError PayloadTypeFlag = 3
)

type SizeData struct {
	Cols uint32 `json:"cols"`
	Rows uint32 `json:"rows"`
}

type IClientMessage interface {
	Validate() error
	DeserializeClientMessage(log log.T, input []byte) (err error)
	SerializeClientMessage(log log.T) (result []byte, err error)
	DeserializeDataStreamAcknowledgeContent(log log.T) (dataStreamAcknowledge AcknowledgeContent, err error)
	DeserializeChannelClosedMessage(log log.T) (channelClosed ChannelClosed, err error)
	DeserializeHandshakeRequest(log log.T) (handshakeRequest HandshakeRequestPayload, err error)
	DeserializeHandshakeComplete(log log.T) (handshakeComplete HandshakeCompletePayload, err error)
}

// ClientMessage represents a message for client to send/receive. ClientMessage Message in MGS is equivalent to MDS' InstanceMessage.
// All client messages are sent in this form to the MGS service.
type ClientMessage struct {
	HeaderLength   uint32
	MessageType    string
	SchemaVersion  uint32
	CreatedDate    uint64
	SequenceNumber int64
	Flags          uint64
	MessageId      uuid.UUID
	PayloadDigest  []byte
	PayloadType    uint32
	PayloadLength  uint32
	Payload        []byte
}

// * HL - HeaderLength is a 4 byte integer that represents the header length.
// * MessageType is a 32 byte UTF-8 string containing the message type.
// * SchemaVersion is a 4 byte integer containing the message schema version number.
// * CreatedDate is an 8 byte integer containing the message create epoch millis in UTC.
// * SequenceNumber is an 8 byte integer containing the message sequence number for serialized message streams.
// * Flags is an 8 byte unsigned integer containing a packed array of control flags:
// *   Bit 0 is SYN - SYN is set (1) when the recipient should consider Seq to be the first message number in the stream
// *   Bit 1 is FIN - FIN is set (1) when this message is the final message in the sequence.
// * MessageId is a 40 byte UTF-8 string containing a random UUID identifying this message.
// * Payload digest is a 32 byte containing the SHA-256 hash of the payload.
// * Payload length is an 4 byte unsigned integer containing the byte length of data in the Payload field.
// * Payload is a variable length byte data.
//
// * | HL|         MessageType           |Ver|  CD   |  Seq  | Flags |
// * |         MessageId                     |           Digest              | PayType | PayLen|
// * |         Payload      			|

const (
	ClientMessage_HLLength             = 4
	ClientMessage_MessageTypeLength    = 32
	ClientMessage_SchemaVersionLength  = 4
	ClientMessage_CreatedDateLength    = 8
	ClientMessage_SequenceNumberLength = 8
	ClientMessage_FlagsLength          = 8
	ClientMessage_MessageIdLength      = 16
	ClientMessage_PayloadDigestLength  = 32
	ClientMessage_PayloadTypeLength    = 4
	ClientMessage_PayloadLengthLength  = 4
)

const (
	ClientMessage_HLOffset             = 0
	ClientMessage_MessageTypeOffset    = ClientMessage_HLOffset + ClientMessage_HLLength
	ClientMessage_SchemaVersionOffset  = ClientMessage_MessageTypeOffset + ClientMessage_MessageTypeLength
	ClientMessage_CreatedDateOffset    = ClientMessage_SchemaVersionOffset + ClientMessage_SchemaVersionLength
	ClientMessage_SequenceNumberOffset = ClientMessage_CreatedDateOffset + ClientMessage_CreatedDateLength
	ClientMessage_FlagsOffset          = ClientMessage_SequenceNumberOffset + ClientMessage_SequenceNumberLength
	ClientMessage_MessageIdOffset      = ClientMessage_FlagsOffset + ClientMessage_FlagsLength
	ClientMessage_PayloadDigestOffset  = ClientMessage_MessageIdOffset + ClientMessage_MessageIdLength
	ClientMessage_PayloadTypeOffset    = ClientMessage_PayloadDigestOffset + ClientMessage_PayloadDigestLength
	ClientMessage_PayloadLengthOffset  = ClientMessage_PayloadTypeOffset + ClientMessage_PayloadTypeLength
	ClientMessage_PayloadOffset        = ClientMessage_PayloadLengthOffset + ClientMessage_PayloadLengthLength
)
