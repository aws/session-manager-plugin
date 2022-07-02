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
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/aws/session-manager-plugin/src/log"
	"github.com/twinj/uuid"
)

// DeserializeClientMessage deserializes the byte array into an ClientMessage message.
// * Payload is a variable length byte data.
// * | HL|         MessageType           |Ver|  CD   |  Seq  | Flags |
// * |         MessageId                     |           Digest              | PayType | PayLen|
// * |         Payload      			|
func (clientMessage *ClientMessage) DeserializeClientMessage(log log.T, input []byte) (err error) {
	clientMessage.MessageType, err = getString(log, input, ClientMessage_MessageTypeOffset, ClientMessage_MessageTypeLength)
	if err != nil {
		log.Errorf("Could not deserialize field MessageType with error: %v", err)
		return err
	}
	clientMessage.SchemaVersion, err = getUInteger(log, input, ClientMessage_SchemaVersionOffset)
	if err != nil {
		log.Errorf("Could not deserialize field SchemaVersion with error: %v", err)
		return err
	}
	clientMessage.CreatedDate, err = getULong(log, input, ClientMessage_CreatedDateOffset)
	if err != nil {
		log.Errorf("Could not deserialize field CreatedDate with error: %v", err)
		return err
	}
	clientMessage.SequenceNumber, err = getLong(log, input, ClientMessage_SequenceNumberOffset)
	if err != nil {
		log.Errorf("Could not deserialize field SequenceNumber with error: %v", err)
		return err
	}
	clientMessage.Flags, err = getULong(log, input, ClientMessage_FlagsOffset)
	if err != nil {
		log.Errorf("Could not deserialize field Flags with error: %v", err)
		return err
	}
	clientMessage.MessageId, err = getUuid(log, input, ClientMessage_MessageIdOffset)
	if err != nil {
		log.Errorf("Could not deserialize field MessageId with error: %v", err)
		return err
	}
	clientMessage.PayloadDigest, err = getBytes(log, input, ClientMessage_PayloadDigestOffset, ClientMessage_PayloadDigestLength)
	if err != nil {
		log.Errorf("Could not deserialize field PayloadDigest with error: %v", err)
		return err
	}
	clientMessage.PayloadType, err = getUInteger(log, input, ClientMessage_PayloadTypeOffset)
	if err != nil {
		log.Errorf("Could not deserialize field PayloadType with error: %v", err)
		return err
	}
	clientMessage.PayloadLength, err = getUInteger(log, input, ClientMessage_PayloadLengthOffset)

	headerLength, herr := getUInteger(log, input, ClientMessage_HLOffset)
	if herr != nil {
		log.Errorf("Could not deserialize field HeaderLength with error: %v", err)
		return err
	}

	clientMessage.HeaderLength = headerLength
	clientMessage.Payload = input[headerLength+ClientMessage_PayloadLengthLength:]

	return err
}

// getString get a string value from the byte array starting from the specified offset to the defined length.
func getString(log log.T, byteArray []byte, offset int, stringLength int) (result string, err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+stringLength-1 > byteArrayLength-1 || offset < 0 {
		log.Error("getString failed: Offset is invalid.")
		return "", errors.New("Offset is outside the byte array.")
	}

	//remove nulls from the bytes array
	b := bytes.Trim(byteArray[offset:offset+stringLength], "\x00")

	return strings.TrimSpace(string(b)), nil
}

// getUInteger gets an unsigned integer
func getUInteger(log log.T, byteArray []byte, offset int) (result uint32, err error) {
	var temp int32
	temp, err = getInteger(log, byteArray, offset)
	return uint32(temp), err
}

// getInteger gets an integer value from a byte array starting from the specified offset.
func getInteger(log log.T, byteArray []byte, offset int) (result int32, err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+4 > byteArrayLength || offset < 0 {
		log.Error("getInteger failed: Offset is invalid.")
		return 0, errors.New("Offset is bigger than the byte array.")
	}
	return bytesToInteger(log, byteArray[offset:offset+4])
}

// bytesToInteger gets an integer from a byte array.
func bytesToInteger(log log.T, input []byte) (result int32, err error) {
	var res int32
	inputLength := len(input)
	if inputLength != 4 {
		log.Error("bytesToInteger failed: input array size is not equal to 4.")
		return 0, errors.New("Input array size is not equal to 4.")
	}
	buf := bytes.NewBuffer(input)
	binary.Read(buf, binary.BigEndian, &res)
	return res, nil
}

// getULong gets an unsigned long integer
func getULong(log log.T, byteArray []byte, offset int) (result uint64, err error) {
	var temp int64
	temp, err = getLong(log, byteArray, offset)
	return uint64(temp), err
}

// getLong gets a long integer value from a byte array starting from the specified offset. 64 bit.
func getLong(log log.T, byteArray []byte, offset int) (result int64, err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+8 > byteArrayLength || offset < 0 {
		log.Error("getLong failed: Offset is invalid.")
		return 0, errors.New("Offset is outside the byte array.")
	}
	return bytesToLong(log, byteArray[offset:offset+8])
}

// bytesToLong gets a Long integer from a byte array.
func bytesToLong(log log.T, input []byte) (result int64, err error) {
	var res int64
	inputLength := len(input)
	if inputLength != 8 {
		log.Error("bytesToLong failed: input array size is not equal to 8.")
		return 0, errors.New("Input array size is not equal to 8.")
	}
	buf := bytes.NewBuffer(input)
	binary.Read(buf, binary.BigEndian, &res)
	return res, nil
}

// getUuid gets the 128bit uuid from an array of bytes starting from the offset.
func getUuid(log log.T, byteArray []byte, offset int) (result uuid.UUID, err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+16-1 > byteArrayLength-1 || offset < 0 {
		log.Error("getUuid failed: Offset is invalid.")
		return nil, errors.New("Offset is outside the byte array.")
	}

	leastSignificantLong, err := getLong(log, byteArray, offset)
	if err != nil {
		log.Error("getUuid failed: failed to get uuid LSBs Long value.")
		return nil, errors.New("Failed to get uuid LSBs long value.")
	}

	leastSignificantBytes, err := longToBytes(log, leastSignificantLong)
	if err != nil {
		log.Error("getUuid failed: failed to get uuid LSBs bytes value.")
		return nil, errors.New("Failed to get uuid LSBs bytes value.")
	}

	mostSignificantLong, err := getLong(log, byteArray, offset+8)
	if err != nil {
		log.Error("getUuid failed: failed to get uuid MSBs Long value.")
		return nil, errors.New("Failed to get uuid MSBs long value.")
	}

	mostSignificantBytes, err := longToBytes(log, mostSignificantLong)
	if err != nil {
		log.Error("getUuid failed: failed to get uuid MSBs bytes value.")
		return nil, errors.New("Failed to get uuid MSBs bytes value.")
	}

	uuidBytes := append(mostSignificantBytes, leastSignificantBytes...)

	return uuid.New(uuidBytes), nil
}

// longToBytes gets bytes array from a long integer.
func longToBytes(log log.T, input int64) (result []byte, err error) {
	buf := new(bytes.Buffer)
	binary.Write(buf, binary.BigEndian, input)
	if buf.Len() != 8 {
		log.Error("longToBytes failed: buffer output length is not equal to 8.")
		return make([]byte, 8), errors.New("Input array size is not equal to 8.")
	}

	return buf.Bytes(), nil
}

// getBytes gets an array of bytes starting from the offset.
func getBytes(log log.T, byteArray []byte, offset int, byteLength int) (result []byte, err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+byteLength-1 > byteArrayLength-1 || offset < 0 {
		log.Error("getBytes failed: Offset is invalid.")
		return make([]byte, byteLength), errors.New("Offset is outside the byte array.")
	}
	return byteArray[offset : offset+byteLength], nil
}

// Validate returns error if the message is invalid
func (clientMessage *ClientMessage) Validate() error {
	if StartPublicationMessage == clientMessage.MessageType ||
		PausePublicationMessage == clientMessage.MessageType {
		return nil
	}
	if clientMessage.HeaderLength == 0 {
		return errors.New("HeaderLength cannot be zero")
	}
	if clientMessage.MessageType == "" {
		return errors.New("MessageType is missing")
	}
	if clientMessage.CreatedDate == 0 {
		return errors.New("CreatedDate is missing")
	}
	if clientMessage.PayloadLength != 0 {
		hasher := sha256.New()
		hasher.Write(clientMessage.Payload)
		if !bytes.Equal(hasher.Sum(nil), clientMessage.PayloadDigest) {
			return errors.New("payload Hash is not valid")
		}
	}
	return nil
}

// SerializeClientMessage serializes ClientMessage message into a byte array.
// * Payload is a variable length byte data.
// * | HL|         MessageType           |Ver|  CD   |  Seq  | Flags |
// * |         MessageId                     |           Digest              |PayType| PayLen|
// * |         Payload      			|
func (clientMessage *ClientMessage) SerializeClientMessage(log log.T) (result []byte, err error) {
	payloadLength := uint32(len(clientMessage.Payload))
	headerLength := uint32(ClientMessage_PayloadLengthOffset)
	// Set payload length
	clientMessage.PayloadLength = payloadLength

	totalMessageLength := headerLength + ClientMessage_PayloadLengthLength + payloadLength
	result = make([]byte, totalMessageLength)

	err = putUInteger(log, result, ClientMessage_HLOffset, headerLength)
	if err != nil {
		log.Errorf("Could not serialize HeaderLength with error: %v", err)
		return make([]byte, 1), err
	}

	startPosition := ClientMessage_MessageTypeOffset
	endPosition := ClientMessage_MessageTypeOffset + ClientMessage_MessageTypeLength - 1
	err = putString(log, result, startPosition, endPosition, clientMessage.MessageType)
	if err != nil {
		log.Errorf("Could not serialize MessageType with error: %v", err)
		return make([]byte, 1), err
	}

	err = putUInteger(log, result, ClientMessage_SchemaVersionOffset, clientMessage.SchemaVersion)
	if err != nil {
		log.Errorf("Could not serialize SchemaVersion with error: %v", err)
		return make([]byte, 1), err
	}

	err = putULong(log, result, ClientMessage_CreatedDateOffset, clientMessage.CreatedDate)
	if err != nil {
		log.Errorf("Could not serialize CreatedDate with error: %v", err)
		return make([]byte, 1), err
	}

	err = putLong(log, result, ClientMessage_SequenceNumberOffset, clientMessage.SequenceNumber)
	if err != nil {
		log.Errorf("Could not serialize SequenceNumber with error: %v", err)
		return make([]byte, 1), err
	}

	err = putULong(log, result, ClientMessage_FlagsOffset, clientMessage.Flags)
	if err != nil {
		log.Errorf("Could not serialize Flags with error: %v", err)
		return make([]byte, 1), err
	}

	err = putUuid(log, result, ClientMessage_MessageIdOffset, clientMessage.MessageId)
	if err != nil {
		log.Errorf("Could not serialize MessageId with error: %v", err)
		return make([]byte, 1), err
	}

	hasher := sha256.New()
	hasher.Write(clientMessage.Payload)

	startPosition = ClientMessage_PayloadDigestOffset
	endPosition = ClientMessage_PayloadDigestOffset + ClientMessage_PayloadDigestLength - 1
	err = putBytes(log, result, startPosition, endPosition, hasher.Sum(nil))
	if err != nil {
		log.Errorf("Could not serialize PayloadDigest with error: %v", err)
		return make([]byte, 1), err
	}

	err = putUInteger(log, result, ClientMessage_PayloadTypeOffset, clientMessage.PayloadType)
	if err != nil {
		log.Errorf("Could not serialize PayloadType with error: %v", err)
		return make([]byte, 1), err
	}

	err = putUInteger(log, result, ClientMessage_PayloadLengthOffset, clientMessage.PayloadLength)
	if err != nil {
		log.Errorf("Could not serialize PayloadLength with error: %v", err)
		return make([]byte, 1), err
	}

	startPosition = ClientMessage_PayloadOffset
	endPosition = ClientMessage_PayloadOffset + int(payloadLength) - 1
	err = putBytes(log, result, startPosition, endPosition, clientMessage.Payload)
	if err != nil {
		log.Errorf("Could not serialize Payload with error: %v", err)
		return make([]byte, 1), err
	}

	return result, nil
}

// putUInteger puts an unsigned integer
func putUInteger(log log.T, byteArray []byte, offset int, value uint32) (err error) {
	return putInteger(log, byteArray, offset, int32(value))
}

// putInteger puts an integer value to a byte array starting from the specified offset.
func putInteger(log log.T, byteArray []byte, offset int, value int32) (err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+4 > byteArrayLength || offset < 0 {
		log.Error("putInteger failed: Offset is invalid.")
		return errors.New("Offset is outside the byte array.")
	}

	bytes, err := integerToBytes(log, value)
	if err != nil {
		log.Error("putInteger failed: getBytesFromInteger Failed.")
		return err
	}

	copy(byteArray[offset:offset+4], bytes)
	return nil
}

// integerToBytes gets bytes array from an integer.
func integerToBytes(log log.T, input int32) (result []byte, err error) {
	buf := new(bytes.Buffer)
	binary.Write(buf, binary.BigEndian, input)
	if buf.Len() != 4 {
		log.Error("integerToBytes failed: buffer output length is not equal to 4.")
		return make([]byte, 4), errors.New("Input array size is not equal to 4.")
	}

	return buf.Bytes(), nil
}

// putString puts a string value to a byte array starting from the specified offset.
func putString(log log.T, byteArray []byte, offsetStart int, offsetEnd int, inputString string) (err error) {
	byteArrayLength := len(byteArray)
	if offsetStart > byteArrayLength-1 || offsetEnd > byteArrayLength-1 || offsetStart > offsetEnd || offsetStart < 0 {
		log.Error("putString failed: Offset is invalid.")
		return errors.New("Offset is outside the byte array.")
	}

	if offsetEnd-offsetStart+1 < len(inputString) {
		log.Error("putString failed: Not enough space to save the string.")
		return errors.New("Not enough space to save the string.")
	}

	// wipe out the array location first and then insert the new value.
	for i := offsetStart; i <= offsetEnd; i++ {
		byteArray[i] = ' '
	}

	copy(byteArray[offsetStart:offsetEnd+1], inputString)
	return nil
}

// putBytes puts bytes into the array at the correct offset.
func putBytes(log log.T, byteArray []byte, offsetStart int, offsetEnd int, inputBytes []byte) (err error) {
	byteArrayLength := len(byteArray)
	if offsetStart > byteArrayLength-1 || offsetEnd > byteArrayLength-1 || offsetStart > offsetEnd || offsetStart < 0 {
		log.Error("putBytes failed: Offset is invalid.")
		return errors.New("Offset is outside the byte array.")
	}

	if offsetEnd-offsetStart+1 != len(inputBytes) {
		log.Error("putBytes failed: Not enough space to save the bytes.")
		return errors.New("Not enough space to save the bytes.")
	}

	copy(byteArray[offsetStart:offsetEnd+1], inputBytes)
	return nil
}

// putUuid puts the 128 bit uuid to an array of bytes starting from the offset.
func putUuid(log log.T, byteArray []byte, offset int, input uuid.UUID) (err error) {
	if input == nil {
		log.Error("putUuid failed: input is null.")
		return errors.New("putUuid failed: input is null.")
	}

	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+16-1 > byteArrayLength-1 || offset < 0 {
		log.Error("putUuid failed: Offset is invalid.")
		return errors.New("Offset is outside the byte array.")
	}

	leastSignificantLong, err := bytesToLong(log, input.Bytes()[8:16])
	if err != nil {
		log.Error("putUuid failed: Failed to get leastSignificant Long value.")
		return errors.New("Failed to get leastSignificant Long value.")
	}

	mostSignificantLong, err := bytesToLong(log, input.Bytes()[0:8])
	if err != nil {
		log.Error("putUuid failed: Failed to get mostSignificantLong Long value.")
		return errors.New("Failed to get mostSignificantLong Long value.")
	}

	err = putLong(log, byteArray, offset, leastSignificantLong)
	if err != nil {
		log.Error("putUuid failed: Failed to put leastSignificantLong Long value.")
		return errors.New("Failed to put leastSignificantLong Long value.")
	}

	err = putLong(log, byteArray, offset+8, mostSignificantLong)
	if err != nil {
		log.Error("putUuid failed: Failed to put mostSignificantLong Long value.")
		return errors.New("Failed to put mostSignificantLong Long value.")
	}

	return nil
}

// putLong puts a long integer value to a byte array starting from the specified offset.
func putLong(log log.T, byteArray []byte, offset int, value int64) (err error) {
	byteArrayLength := len(byteArray)
	if offset > byteArrayLength-1 || offset+8 > byteArrayLength || offset < 0 {
		log.Error("putInteger failed: Offset is invalid.")
		return errors.New("Offset is outside the byte array.")
	}

	mbytes, err := longToBytes(log, value)
	if err != nil {
		log.Error("putInteger failed: getBytesFromInteger Failed.")
		return err
	}

	copy(byteArray[offset:offset+8], mbytes)
	return nil
}

// putULong puts an unsigned long integer.
func putULong(log log.T, byteArray []byte, offset int, value uint64) (err error) {
	return putLong(log, byteArray, offset, int64(value))
}

// SerializeClientMessagePayload marshals payloads for all session specific messages into bytes.
func SerializeClientMessagePayload(log log.T, obj interface{}) (reply []byte, err error) {
	reply, err = json.Marshal(obj)
	if err != nil {
		log.Errorf("Could not serialize message with err: %s", err)
	}
	return
}

// SerializeClientMessageWithAcknowledgeContent marshals client message with payloads of acknowledge contents into bytes.
func SerializeClientMessageWithAcknowledgeContent(log log.T, acknowledgeContent AcknowledgeContent) (reply []byte, err error) {

	acknowledgeContentBytes, err := SerializeClientMessagePayload(log, acknowledgeContent)
	if err != nil {
		// should not happen
		log.Errorf("Cannot marshal acknowledge content to json string: %v", acknowledgeContentBytes)
		return
	}

	uuid.SwitchFormat(uuid.CleanHyphen)
	messageId := uuid.NewV4()
	clientMessage := ClientMessage{
		MessageType:    AcknowledgeMessage,
		SchemaVersion:  1,
		CreatedDate:    uint64(time.Now().UnixNano() / 1000000),
		SequenceNumber: 0,
		Flags:          3,
		MessageId:      messageId,
		Payload:        acknowledgeContentBytes,
	}

	reply, err = clientMessage.SerializeClientMessage(log)
	if err != nil {
		log.Errorf("Error serializing client message with acknowledge content err: %v", err)
	}

	return
}

// DeserializeDataStreamAcknowledgeContent parses acknowledge content from payload of ClientMessage.
func (clientMessage *ClientMessage) DeserializeDataStreamAcknowledgeContent(log log.T) (dataStreamAcknowledge AcknowledgeContent, err error) {
	if clientMessage.MessageType != AcknowledgeMessage {
		err = fmt.Errorf("ClientMessage is not of type AcknowledgeMessage. Found message type: %s", clientMessage.MessageType)
		return
	}

	err = json.Unmarshal(clientMessage.Payload, &dataStreamAcknowledge)
	if err != nil {
		log.Errorf("Could not deserialize rawMessage: %s", err)
	}
	return
}

// DeserializeChannelClosedMessage parses channelClosed message from payload of ClientMessage.
func (clientMessage *ClientMessage) DeserializeChannelClosedMessage(log log.T) (channelClosed ChannelClosed, err error) {
	if clientMessage.MessageType != ChannelClosedMessage {
		err = fmt.Errorf("ClientMessage is not of type ChannelClosed. Found message type: %s", clientMessage.MessageType)
		return
	}

	err = json.Unmarshal(clientMessage.Payload, &channelClosed)
	if err != nil {
		log.Errorf("Could not deserialize rawMessage: %s", err)
	}
	return
}

func (clientMessage *ClientMessage) DeserializeHandshakeRequest(log log.T) (handshakeRequest HandshakeRequestPayload, err error) {
	if clientMessage.PayloadType != uint32(HandshakeRequestPayloadType) {
		err = log.Errorf("ClientMessage PayloadType is not of type HandshakeRequestPayloadType. Found payload type: %d",
			clientMessage.PayloadType)
		return
	}

	err = json.Unmarshal(clientMessage.Payload, &handshakeRequest)
	if err != nil {
		log.Errorf("Could not deserialize rawMessage: %s", err)
	}
	return
}

func (clientMessage *ClientMessage) DeserializeHandshakeComplete(log log.T) (handshakeComplete HandshakeCompletePayload, err error) {
	if clientMessage.PayloadType != uint32(HandshakeCompletePayloadType) {
		err = log.Errorf("ClientMessage PayloadType is not of type HandshakeCompletePayloadType. Found payload type: %d",
			clientMessage.PayloadType)
		return
	}

	err = json.Unmarshal(clientMessage.Payload, &handshakeComplete)
	if err != nil {
		log.Errorf("Could not deserialize rawMessage, %s : %s", clientMessage.Payload, err)
	}
	return
}
