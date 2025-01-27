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

// datachannel package implement data channel for interactive sessions.
package datachannel

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/signer/v4"
	"github.com/aws/aws-sdk-go/service/kms/kmsiface"
	communicatorMocks "github.com/aws/session-manager-plugin/src/communicator/mocks"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/encryption"
	"github.com/aws/session-manager-plugin/src/encryption/mocks"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/version"
	"github.com/gorilla/websocket"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/twinj/uuid"
)

var (
	outputMessageType                           = message.OutputStreamMessage
	serializedClientMessages, streamingMessages = getClientAndStreamingMessageList(7)
	logger                                      = log.NewMockLog()
	mockWsChannel                               = &communicatorMocks.IWebSocketChannel{}
	streamUrl                                   = "stream-url"
	channelToken                                = "channel-token"
	sessionId                                   = "session-id"
	clientId                                    = "client-id"
	kmsKeyId                                    = "some-key-id"
	instanceId                                  = "some-instance-id"
	cipherTextKey                               = []byte("cipher-text-key")
	mockLogger                                  = log.NewMockLog()
	messageType                                 = message.OutputStreamMessage
	schemaVersion                               = uint32(1)
	messageId                                   = "dd01e56b-ff48-483e-a508-b5f073f31b16"
	createdDate                                 = uint64(1503434274948)
	payload                                     = []byte("testPayload")
	streamDataSequenceNumber                    = int64(0)
	expectedSequenceNumber                      = int64(0)
	region                                      = "us-east-1"
	mockSigner                                  = &v4.Signer{Credentials: credentials.NewStaticCredentials("AKID", "SECRET", "SESSION")}
)

func TestInitialize(t *testing.T) {
	datachannel := DataChannel{}
	isAwsCliUpgradeNeeded := false
	datachannel.Initialize(mockLogger, clientId, sessionId, instanceId, isAwsCliUpgradeNeeded)

	assert.Equal(t, config.RolePublishSubscribe, datachannel.Role)
	assert.Equal(t, clientId, datachannel.ClientId)
	assert.True(t, datachannel.ExpectedSequenceNumber == 0)
	assert.True(t, datachannel.StreamDataSequenceNumber == 0)
	assert.NotNil(t, datachannel.OutgoingMessageBuffer)
	assert.NotNil(t, datachannel.IncomingMessageBuffer)
	assert.Equal(t, float64(config.DefaultRoundTripTime), datachannel.RoundTripTime)
	assert.Equal(t, float64(config.DefaultRoundTripTimeVariation), datachannel.RoundTripTimeVariation)
	assert.Equal(t, config.DefaultTransmissionTimeout, datachannel.RetransmissionTimeout)
	assert.NotNil(t, datachannel.wsChannel)
}

func TestSetWebsocket(t *testing.T) {
	datachannel := getDataChannel()

	mockWsChannel.On("GetStreamUrl").Return(streamUrl)
	mockWsChannel.On("GetChannelToken").Return(channelToken)
	mockWsChannel.On("Initialize", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).Return(nil)

	datachannel.SetWebsocket(mockLogger, streamUrl, channelToken, region, mockSigner)

	assert.Equal(t, streamUrl, datachannel.wsChannel.GetStreamUrl())
	assert.Equal(t, channelToken, datachannel.wsChannel.GetChannelToken())
	mockWsChannel.AssertExpectations(t)
}

func TestReconnect(t *testing.T) {
	datachannel := getDataChannel()

	mockWsChannel.On("Close", mock.Anything).Return(nil)
	mockWsChannel.On("Open", mock.Anything).Return(nil)
	mockWsChannel.On("SendMessage", mock.Anything, mock.Anything, mock.Anything).Return(nil)

	// test reconnect
	err := datachannel.Reconnect(mockLogger)

	assert.Nil(t, err)
	mockWsChannel.AssertExpectations(t)
}

func TestOpen(t *testing.T) {
	datachannel := getDataChannel()

	mockWsChannel.On("Open", mock.Anything).Return(nil)

	err := datachannel.Open(mockLogger)

	assert.Nil(t, err)
	mockWsChannel.AssertExpectations(t)
}

func TestClose(t *testing.T) {
	datachannel := getDataChannel()

	mockWsChannel.On("Close", mock.Anything).Return(nil)

	// test close
	err := datachannel.Close(mockLogger)

	assert.Nil(t, err)
	mockWsChannel.AssertExpectations(t)
}

func TestFinalizeDataChannelHandshake(t *testing.T) {
	datachannel := getDataChannel()
	mockWsChannel.On("SendMessage", mock.Anything, mock.Anything, mock.Anything).Return(nil)
	mockWsChannel.On("GetStreamUrl").Return(streamUrl)

	err := datachannel.FinalizeDataChannelHandshake(mockLogger, channelToken)

	assert.Nil(t, err)
	assert.Equal(t, streamUrl, datachannel.wsChannel.GetStreamUrl())
	mockWsChannel.AssertExpectations(t)
}

func TestSendMessage(t *testing.T) {
	datachannel := getDataChannel()
	mockWsChannel.On("SendMessage", mock.Anything, mock.Anything, mock.Anything).Return(nil)

	err := datachannel.SendMessage(mockLogger, []byte{10}, websocket.BinaryMessage)

	assert.Nil(t, err)
	mockWsChannel.AssertExpectations(t)
}

func TestSendInputDataMessage(t *testing.T) {
	dataChannel := getDataChannel()

	mockWsChannel.On("SendMessage", mock.Anything, mock.Anything, mock.Anything).Return(nil)

	dataChannel.SendInputDataMessage(mockLogger, message.Output, payload)

	assert.Equal(t, streamDataSequenceNumber+1, dataChannel.StreamDataSequenceNumber)
	assert.Equal(t, 1, dataChannel.OutgoingMessageBuffer.Messages.Len())
	mockWsChannel.AssertExpectations(t)
}

func TestProcessAcknowledgedMessage(t *testing.T) {
	dataChannel := getDataChannel()
	dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[0])
	dataStreamAcknowledgeContent := message.AcknowledgeContent{
		MessageType:         messageType,
		MessageId:           messageId,
		SequenceNumber:      0,
		IsSequentialMessage: true,
	}
	dataChannel.ProcessAcknowledgedMessage(mockLogger, dataStreamAcknowledgeContent)
	assert.Equal(t, 0, dataChannel.OutgoingMessageBuffer.Messages.Len())
}

func TestCalculateRetransmissionTimeout(t *testing.T) {
	dataChannel := getDataChannel()
	GetRoundTripTime = func(streamingMessage StreamingMessage) time.Duration {
		return time.Duration(140 * time.Millisecond)
	}

	dataChannel.CalculateRetransmissionTimeout(mockLogger, streamingMessages[0])
	assert.Equal(t, int64(105), int64(time.Duration(dataChannel.RoundTripTime)/time.Millisecond))
	assert.Equal(t, int64(10), int64(time.Duration(dataChannel.RoundTripTimeVariation)/time.Millisecond))
	assert.Equal(t, int64(145), int64(dataChannel.RetransmissionTimeout/time.Millisecond))
}

func TestAddDataToOutgoingMessageBuffer(t *testing.T) {
	dataChannel := getDataChannel()
	dataChannel.OutgoingMessageBuffer.Capacity = 2

	dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[0])
	assert.Equal(t, 1, dataChannel.OutgoingMessageBuffer.Messages.Len())
	bufferedStreamMessage := dataChannel.OutgoingMessageBuffer.Messages.Front().Value.(StreamingMessage)
	assert.Equal(t, int64(0), bufferedStreamMessage.SequenceNumber)

	dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[1])
	assert.Equal(t, 2, dataChannel.OutgoingMessageBuffer.Messages.Len())
	bufferedStreamMessage = dataChannel.OutgoingMessageBuffer.Messages.Front().Value.(StreamingMessage)
	assert.Equal(t, int64(0), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.OutgoingMessageBuffer.Messages.Back().Value.(StreamingMessage)
	assert.Equal(t, int64(1), bufferedStreamMessage.SequenceNumber)

	dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[2])
	assert.Equal(t, 2, dataChannel.OutgoingMessageBuffer.Messages.Len())
	bufferedStreamMessage = dataChannel.OutgoingMessageBuffer.Messages.Front().Value.(StreamingMessage)
	assert.Equal(t, int64(1), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.OutgoingMessageBuffer.Messages.Back().Value.(StreamingMessage)
	assert.Equal(t, int64(2), bufferedStreamMessage.SequenceNumber)
}

func TestAddDataToIncomingMessageBuffer(t *testing.T) {
	dataChannel := getDataChannel()
	dataChannel.IncomingMessageBuffer.Capacity = 2

	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[0])
	assert.Equal(t, 1, len(dataChannel.IncomingMessageBuffer.Messages))
	bufferedStreamMessage := dataChannel.IncomingMessageBuffer.Messages[0]
	assert.Equal(t, int64(0), bufferedStreamMessage.SequenceNumber)

	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[1])
	assert.Equal(t, 2, len(dataChannel.IncomingMessageBuffer.Messages))
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[0]
	assert.Equal(t, int64(0), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[1]
	assert.Equal(t, int64(1), bufferedStreamMessage.SequenceNumber)

	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[2])
	assert.Equal(t, 2, len(dataChannel.IncomingMessageBuffer.Messages))
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[0]
	assert.Equal(t, int64(0), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[1]
	assert.Equal(t, int64(1), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[2]
	assert.Nil(t, bufferedStreamMessage.Content)
}

func TestRemoveDataFromOutgoingMessageBuffer(t *testing.T) {
	dataChannel := getDataChannel()
	for i := 0; i < 3; i++ {
		dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[i])
	}

	dataChannel.RemoveDataFromOutgoingMessageBuffer(dataChannel.OutgoingMessageBuffer.Messages.Front())
	assert.Equal(t, 2, dataChannel.OutgoingMessageBuffer.Messages.Len())
}

func TestRemoveDataFromIncomingMessageBuffer(t *testing.T) {
	dataChannel := getDataChannel()
	for i := 0; i < 3; i++ {
		dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[i])
	}

	dataChannel.RemoveDataFromIncomingMessageBuffer(0)
	assert.Equal(t, 2, len(dataChannel.IncomingMessageBuffer.Messages))
}

func TestResendStreamDataMessageScheduler(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	for i := 0; i < 3; i++ {
		dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[i])
	}

	var wg sync.WaitGroup
	wg.Add(1)
	// Spawning a separate go routine to close websocket connection.
	// This is required as ResendStreamDataMessageScheduler has a for loop which will continuosly resend data until channel is closed.
	go func() {
		time.Sleep(1 * time.Second)
		wg.Done()
	}()

	SendMessageCallCount := 0
	SendMessageCall = func(log log.T, dataChannel *DataChannel, input []byte, inputType int) error {
		SendMessageCallCount++
		return nil
	}
	dataChannel.ResendStreamDataMessageScheduler(mockLogger)
	wg.Wait()
	assert.True(t, SendMessageCallCount > 1)
}

func TestDataChannelIncomingMessageHandlerForExpectedInputStreamDataMessage(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel

	SendAcknowledgeMessageCallCount := 0
	SendAcknowledgeMessageCall = func(log log.T, dataChannel *DataChannel, streamDataMessage message.ClientMessage) error {
		SendAcknowledgeMessageCallCount++
		return nil
	}

	var handler OutputStreamDataMessageHandler = func(log log.T, outputMessage message.ClientMessage) (bool, error) {
		return true, nil
	}

	var stopHandler Stop

	dataChannel.RegisterOutputStreamHandler(handler, true)
	// First scenario is to test when incoming message sequence number matches with expected sequence number
	// and no message found in IncomingMessageBuffer
	err := dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[0])
	assert.Nil(t, err)
	assert.Equal(t, int64(1), dataChannel.ExpectedSequenceNumber)
	assert.Equal(t, 0, len(dataChannel.IncomingMessageBuffer.Messages))
	assert.Equal(t, 1, SendAcknowledgeMessageCallCount)

	// Second scenario is to test when incoming message sequence number matches with expected sequence number
	// and there are more messages found in IncomingMessageBuffer to be processed
	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[2])
	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[6])
	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[4])
	dataChannel.AddDataToIncomingMessageBuffer(streamingMessages[3])

	err = dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[1])
	assert.Nil(t, err)
	assert.Equal(t, int64(5), dataChannel.ExpectedSequenceNumber)
	assert.Equal(t, 1, len(dataChannel.IncomingMessageBuffer.Messages))

	// All messages from buffer should get processed except sequence number 6 as expected number to be processed at this time is 5
	bufferedStreamMessage := dataChannel.IncomingMessageBuffer.Messages[6]
	assert.Equal(t, int64(6), bufferedStreamMessage.SequenceNumber)
}

func TestDataChannelIncomingMessageHandlerForUnexpectedInputStreamDataMessage(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	dataChannel.IncomingMessageBuffer.Capacity = 2

	SendAcknowledgeMessageCallCount := 0
	SendAcknowledgeMessageCall = func(log log.T, dataChannel *DataChannel, streamDataMessage message.ClientMessage) error {
		SendAcknowledgeMessageCallCount++
		return nil
	}

	var stopHandler Stop

	err := dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[1])
	assert.Nil(t, err)

	err = dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[2])
	assert.Nil(t, err)

	err = dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[3])
	assert.Nil(t, err)

	// Since capacity of IncomingMessageBuffer is 2, stream data with sequence number 3 should be ignored without sending acknowledgement
	assert.Equal(t, expectedSequenceNumber, dataChannel.ExpectedSequenceNumber)
	assert.Equal(t, 2, len(dataChannel.IncomingMessageBuffer.Messages))
	assert.Equal(t, 2, SendAcknowledgeMessageCallCount)

	bufferedStreamMessage := dataChannel.IncomingMessageBuffer.Messages[1]
	assert.Equal(t, int64(1), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[2]
	assert.Equal(t, int64(2), bufferedStreamMessage.SequenceNumber)
	bufferedStreamMessage = dataChannel.IncomingMessageBuffer.Messages[3]
	assert.Nil(t, bufferedStreamMessage.Content)
}

func TestDataChannelIncomingMessageHandlerForAcknowledgeMessage(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	var stopHandler Stop

	for i := 0; i < 3; i++ {
		dataChannel.AddDataToOutgoingMessageBuffer(streamingMessages[i])
	}

	ProcessAcknowledgedMessageCallCount := 0
	ProcessAcknowledgedMessageCall = func(log log.T, dataChannel *DataChannel, acknowledgeMessage message.AcknowledgeContent) error {
		ProcessAcknowledgedMessageCallCount++
		return nil
	}

	acknowledgeContent := message.AcknowledgeContent{
		MessageType:         outputMessageType,
		MessageId:           messageId,
		SequenceNumber:      1,
		IsSequentialMessage: true,
	}
	payload, _ = json.Marshal(acknowledgeContent)
	clientMessage := getClientMessage(0, message.AcknowledgeMessage, uint32(message.Output), payload)
	serializedClientMessage, _ := clientMessage.SerializeClientMessage(logger)
	err := dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessage)

	assert.Nil(t, err)
	assert.Equal(t, 1, ProcessAcknowledgedMessageCallCount)
	assert.Equal(t, 3, dataChannel.OutgoingMessageBuffer.Messages.Len())
}

func TestDataChannelIncomingMessageHandlerForPausePublicationessage(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel

	size := 5
	streamingMessages = make([]StreamingMessage, size)
	serializedClientMessage := make([][]byte, size)
	for i := 0; i < size; i++ {
		clientMessage := getClientMessage(int64(i), message.PausePublicationMessage, uint32(message.Output), []byte(""))
		serializedClientMessage[i], _ = clientMessage.SerializeClientMessage(mockLogger)
		streamingMessages[i] = StreamingMessage{
			serializedClientMessage[i],
			int64(i),
			time.Now(),
			new(int),
		}
	}

	var handler OutputStreamDataMessageHandler = func(log log.T, outputMessage message.ClientMessage) (bool, error) {
		return true, nil
	}

	var stopHandler Stop

	dataChannel.RegisterOutputStreamHandler(handler, true)
	err := dataChannel.OutputMessageHandler(logger, stopHandler, sessionId, serializedClientMessages[0])
	assert.Nil(t, err)
}

func TestHandshakeRequestHandler(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	mockEncrypter := &mocks.IEncrypter{}

	handshakeRequestBytes, _ := json.Marshal(buildHandshakeRequest())
	clientMessage := getClientMessage(0, message.OutputStreamMessage,
		uint32(message.HandshakeRequestPayloadType), handshakeRequestBytes)
	handshakeRequestMessageBytes, _ := clientMessage.SerializeClientMessage(mockLogger)

	newEncrypter = func(log log.T, kmsKeyIdInput string, context map[string]*string, KMSService kmsiface.KMSAPI) (encryption.IEncrypter, error) {
		expectedContext := map[string]*string{"aws:ssm:SessionId": &sessionId, "aws:ssm:TargetId": &instanceId}
		assert.Equal(t, kmsKeyId, kmsKeyIdInput)
		assert.Equal(t, expectedContext, context)
		mockEncrypter.On("GetEncryptedDataKey").Return(cipherTextKey)
		return mockEncrypter, nil
	}
	// Mock sending of encryption challenge
	handshakeResponseMatcher := func(sentData []byte) bool {
		clientMessage := &message.ClientMessage{}
		clientMessage.DeserializeClientMessage(mockLogger, sentData)
		var handshakeResponse = message.HandshakeResponsePayload{}
		json.Unmarshal(clientMessage.Payload, &handshakeResponse)
		// Return true if any other message type (typically to account for acknowledge)
		if clientMessage.MessageType != message.OutputStreamMessage {
			return true
		}

		expectedActions := []message.ProcessedClientAction{}
		processedAction := message.ProcessedClientAction{}
		processedAction.ActionType = message.KMSEncryption
		processedAction.ActionStatus = message.Success
		processedAction.ActionResult = message.KMSEncryptionResponse{
			KMSCipherTextKey: cipherTextKey,
		}
		expectedActions = append(expectedActions, processedAction)

		processedAction = message.ProcessedClientAction{}
		processedAction.ActionType = message.SessionType
		processedAction.ActionStatus = message.Success
		expectedActions = append(expectedActions, processedAction)

		return handshakeResponse.ClientVersion == version.Version &&
			reflect.DeepEqual(handshakeResponse.ProcessedClientActions, expectedActions)
	}
	mockChannel.On("SendMessage", mock.Anything, mock.MatchedBy(handshakeResponseMatcher), mock.Anything).Return(nil)
	dataChannel.OutputMessageHandler(mockLogger, func() {}, sessionId, handshakeRequestMessageBytes)
	assert.Equal(t, mockEncrypter, dataChannel.encryption)
}

func TestHandleOutputMessageForDefaultTypeWithError(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	clientMessage := getClientMessage(0, message.OutputStreamMessage,
		uint32(message.Output), payload)
	rawMessage := []byte("rawMessage")
	var handler OutputStreamDataMessageHandler = func(log log.T, outputMessage message.ClientMessage) (bool, error) {
		return true, log.Errorf("OutputStreamDataMessageHandler Error")
	}
	dataChannel.RegisterOutputStreamHandler(handler, true)

	err := dataChannel.HandleOutputMessage(mockLogger, clientMessage, rawMessage)
	assert.NotNil(t, err)
}

func TestHandleOutputMessageForExitCodePayloadTypeWithError(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel
	clientMessage := getClientMessage(0, message.OutputStreamMessage,
		uint32(message.ExitCode), payload)
	dataChannel.encryptionEnabled = true
	mockEncrypter := &mocks.IEncrypter{}
	dataChannel.encryption = mockEncrypter
	mockErr := fmt.Errorf("Decrypt Error")
	mockEncrypter.On("Decrypt", mock.Anything, mock.Anything).Return([]byte{10, 11, 12}, mockErr)
	rawMessage := []byte("rawMessage")

	err := dataChannel.HandleOutputMessage(mockLogger, clientMessage, rawMessage)
	assert.Equal(t, mockErr, err)
}

func TestHandleHandshakeRequestWithMessageDeserializeError(t *testing.T) {
	dataChannel := getDataChannel()
	handshakeRequestBytes, _ := json.Marshal(buildHandshakeRequest())
	//Using HandshakeCompletePayloadType to trigger the type check error
	clientMessage := getClientMessage(0, message.OutputStreamMessage,
		uint32(message.HandshakeCompletePayloadType), handshakeRequestBytes)

	err := dataChannel.handleHandshakeRequest(mockLogger, clientMessage)
	assert.NotNil(t, err)
	assert.True(t, strings.Contains(err.Error(), "ClientMessage PayloadType is not of type HandshakeRequestPayloadType"))
}

func TestProcessOutputMessageWithHandlers(t *testing.T) {
	dataChannel := getDataChannel()
	mockChannel := &communicatorMocks.IWebSocketChannel{}
	dataChannel.wsChannel = mockChannel

	var handler OutputStreamDataMessageHandler = func(log log.T, outputMessage message.ClientMessage) (bool, error) {
		return true, log.Errorf("OutputStreamDataMessageHandler Error")
	}
	dataChannel.RegisterOutputStreamHandler(handler, true)

	handshakeRequestBytes, _ := json.Marshal(buildHandshakeRequest())
	clientMessage := getClientMessage(0, message.OutputStreamMessage,
		uint32(message.HandshakeCompletePayloadType), handshakeRequestBytes)

	isHandlerReady, err := dataChannel.processOutputMessageWithHandlers(mockLogger, clientMessage)
	assert.NotNil(t, err)
	assert.Equal(t, isHandlerReady, true)
}

func TestProcessSessionTypeHandshakeActionForInteractiveCommands(t *testing.T) {
	actionParams := []byte("{\"SessionType\":\"InteractiveCommands\"}")
	dataChannel := getDataChannel()

	err := dataChannel.ProcessSessionTypeHandshakeAction(actionParams)

	// Test that InteractiveCommands is a valid session type
	assert.Nil(t, err)
	// Test that InteractiveCommands is translated to Standard_Stream in data channel
	assert.Equal(t, config.ShellPluginName, dataChannel.sessionType)
}

func TestProcessSessionTypeHandshakeActionForNonInteractiveCommands(t *testing.T) {
	actionParams := []byte("{\"SessionType\":\"NonInteractiveCommands\"}")
	dataChannel := getDataChannel()

	err := dataChannel.ProcessSessionTypeHandshakeAction(actionParams)

	// Test that NonInteractiveCommands is a valid session type
	assert.Nil(t, err)
	// Test that NonInteractiveCommands is translated to Standard_Stream in data channel
	assert.Equal(t, config.ShellPluginName, dataChannel.sessionType)
}

func buildHandshakeRequest() message.HandshakeRequestPayload {
	handshakeRquest := message.HandshakeRequestPayload{}
	handshakeRquest.AgentVersion = "10.0.0.1"
	handshakeRquest.RequestedClientActions = []message.RequestedClientAction{}

	requestedAction := message.RequestedClientAction{}
	requestedAction.ActionType = message.KMSEncryption
	requestedAction.ActionParameters, _ = json.Marshal(message.KMSEncryptionRequest{KMSKeyID: kmsKeyId})
	handshakeRquest.RequestedClientActions = append(handshakeRquest.RequestedClientActions, requestedAction)

	requestedAction = message.RequestedClientAction{}
	requestedAction.ActionType = message.SessionType
	requestedAction.ActionParameters, _ = json.Marshal(message.SessionTypeRequest{SessionType: config.ShellPluginName})

	handshakeRquest.RequestedClientActions = append(handshakeRquest.RequestedClientActions, requestedAction)

	return handshakeRquest
}

func getDataChannel() *DataChannel {
	dataChannel := &DataChannel{}
	dataChannel.Initialize(mockLogger, clientId, sessionId, instanceId, false)
	dataChannel.wsChannel = mockWsChannel
	return dataChannel
}

// GetClientMessage constructs and returns ClientMessage with given sequenceNumber, messageType & payload
func getClientMessage(sequenceNumber int64, messageType string, payloadType uint32, payload []byte) message.ClientMessage {
	messageUUID, _ := uuid.Parse(messageId)
	clientMessage := message.ClientMessage{
		MessageType:    messageType,
		SchemaVersion:  schemaVersion,
		CreatedDate:    createdDate,
		SequenceNumber: sequenceNumber,
		Flags:          2,
		MessageId:      messageUUID,
		PayloadType:    payloadType,
		Payload:        payload,
	}
	return clientMessage
}

func getClientAndStreamingMessageList(size int) (serializedClientMessage [][]byte, streamingMessages []StreamingMessage) {
	var payload string
	streamingMessages = make([]StreamingMessage, size)
	serializedClientMessage = make([][]byte, size)
	for i := 0; i < size; i++ {
		payload = "testPayload" + strconv.Itoa(i)
		clientMessage := getClientMessage(int64(i), messageType, uint32(message.Output), []byte(payload))
		serializedClientMessage[i], _ = clientMessage.SerializeClientMessage(mockLogger)
		streamingMessages[i] = StreamingMessage{
			serializedClientMessage[i],
			int64(i),
			time.Now(),
			new(int),
		}
	}
	return
}
