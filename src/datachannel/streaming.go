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
	"bytes"
	"container/list"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"reflect"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/kms/kmsiface"
	"github.com/aws/session-manager-plugin/src/communicator"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/encryption"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/service"
	"github.com/aws/session-manager-plugin/src/version"
	"github.com/gorilla/websocket"
	"github.com/twinj/uuid"
)

type IDataChannel interface {
	Initialize(log log.T, clientId string, sessionId string, targetId string, isAwsCliUpgradeNeeded bool)
	SetWebsocket(log log.T, streamUrl string, tokenValue string)
	Reconnect(log log.T) error
	SendFlag(log log.T, flagType message.PayloadTypeFlag) error
	Open(log log.T) error
	Close(log log.T) error
	FinalizeDataChannelHandshake(log log.T, tokenValue string) error
	SendInputDataMessage(log log.T, payloadType message.PayloadType, inputData []byte) error
	ResendStreamDataMessageScheduler(log log.T) error
	ProcessAcknowledgedMessage(log log.T, acknowledgeMessageContent message.AcknowledgeContent) error
	OutputMessageHandler(log log.T, stopHandler Stop, sessionID string, rawMessage []byte) error
	SendAcknowledgeMessage(log log.T, clientMessage message.ClientMessage) error
	AddDataToOutgoingMessageBuffer(streamMessage StreamingMessage)
	RemoveDataFromOutgoingMessageBuffer(streamMessageElement *list.Element)
	AddDataToIncomingMessageBuffer(streamMessage StreamingMessage)
	RemoveDataFromIncomingMessageBuffer(sequenceNumber int64)
	CalculateRetransmissionTimeout(log log.T, streamingMessage StreamingMessage)
	SendMessage(log log.T, input []byte, inputType int) error
	RegisterOutputStreamHandler(handler OutputStreamDataMessageHandler, isSessionSpecificHandler bool)
	DeregisterOutputStreamHandler(handler OutputStreamDataMessageHandler)
	IsSessionTypeSet() chan bool
	IsStreamMessageResendTimeout() chan bool
	GetSessionType() string
	SetSessionType(sessionType string)
	GetSessionProperties() interface{}
	GetWsChannel() communicator.IWebSocketChannel
	SetWsChannel(wsChannel communicator.IWebSocketChannel)
	GetStreamDataSequenceNumber() int64
	GetAgentVersion() string
	SetAgentVersion(agentVersion string)
}

// DataChannel used for communication between the mgs and the cli.
type DataChannel struct {
	wsChannel             communicator.IWebSocketChannel
	Role                  string
	ClientId              string
	SessionId             string
	TargetId              string
	IsAwsCliUpgradeNeeded bool
	//records sequence number of last acknowledged message received over data channel
	ExpectedSequenceNumber int64
	//records sequence number of last stream data message sent over data channel
	StreamDataSequenceNumber int64
	//buffer to store outgoing stream messages until acknowledged
	//using linked list for this buffer as access to oldest message is required and it support faster deletion from any position of list
	OutgoingMessageBuffer ListMessageBuffer
	//buffer to store incoming stream messages if received out of sequence
	//using map for this buffer as incoming messages can be out of order and retrieval would be faster by sequenceId
	IncomingMessageBuffer MapMessageBuffer
	//round trip time of latest acknowledged message
	RoundTripTime float64
	//round trip time variation of latest acknowledged message
	RoundTripTimeVariation float64
	//timeout used for resending unacknowledged message
	RetransmissionTimeout time.Duration
	// Encrypter to encrypt/decrypt if agent requests encryption
	encryption        encryption.IEncrypter
	encryptionEnabled bool

	// SessionType
	sessionType       string
	isSessionTypeSet  chan bool
	sessionProperties interface{}

	// Used to detect if resending a streaming message reaches timeout
	isStreamMessageResendTimeout chan bool

	// Handles data on output stream. Output stream is data outputted by the SSM agent and received here.
	outputStreamHandlers        []OutputStreamDataMessageHandler
	isSessionSpecificHandlerSet bool

	// AgentVersion received during handshake
	agentVersion string
}

type ListMessageBuffer struct {
	Messages *list.List
	Capacity int
	Mutex    *sync.Mutex
}

type MapMessageBuffer struct {
	Messages map[int64]StreamingMessage
	Capacity int
	Mutex    *sync.Mutex
}

type StreamingMessage struct {
	Content        []byte
	SequenceNumber int64
	LastSentTime   time.Time
	ResendAttempt  *int
}

type OutputStreamDataMessageHandler func(log log.T, streamDataMessage message.ClientMessage) (bool, error)

type Stop func()

var SendAcknowledgeMessageCall = func(log log.T, dataChannel *DataChannel, streamDataMessage message.ClientMessage) error {
	return dataChannel.SendAcknowledgeMessage(log, streamDataMessage)
}

var ProcessAcknowledgedMessageCall = func(log log.T, dataChannel *DataChannel, acknowledgeMessage message.AcknowledgeContent) error {
	return dataChannel.ProcessAcknowledgedMessage(log, acknowledgeMessage)
}

var SendMessageCall = func(log log.T, dataChannel *DataChannel, input []byte, inputType int) error {
	return dataChannel.SendMessage(log, input, inputType)
}

var GetRoundTripTime = func(streamingMessage StreamingMessage) time.Duration {
	return time.Since(streamingMessage.LastSentTime)
}

var newEncrypter = func(log log.T, kmsKeyId string, encryptionConext map[string]*string, kmsService kmsiface.KMSAPI) (encryption.IEncrypter, error) {
	return encryption.NewEncrypter(log, kmsKeyId, encryptionConext, kmsService)
}

// Initialize populates the data channel object with the correct values.
func (dataChannel *DataChannel) Initialize(log log.T, clientId string, sessionId string, targetId string, isAwsCliUpgradeNeeded bool) {
	//open data channel as publish_subscribe
	log.Debugf("Calling Initialize Datachannel for role: %s", config.RolePublishSubscribe)

	dataChannel.Role = config.RolePublishSubscribe
	dataChannel.ClientId = clientId
	dataChannel.SessionId = sessionId
	dataChannel.TargetId = targetId
	dataChannel.ExpectedSequenceNumber = 0
	dataChannel.StreamDataSequenceNumber = 0
	dataChannel.OutgoingMessageBuffer = ListMessageBuffer{
		list.New(),
		config.OutgoingMessageBufferCapacity,
		&sync.Mutex{},
	}
	dataChannel.IncomingMessageBuffer = MapMessageBuffer{
		make(map[int64]StreamingMessage),
		config.IncomingMessageBufferCapacity,
		&sync.Mutex{},
	}
	dataChannel.RoundTripTime = float64(config.DefaultRoundTripTime)
	dataChannel.RoundTripTimeVariation = config.DefaultRoundTripTimeVariation
	dataChannel.RetransmissionTimeout = config.DefaultTransmissionTimeout
	dataChannel.wsChannel = &communicator.WebSocketChannel{}
	dataChannel.encryptionEnabled = false
	dataChannel.isSessionTypeSet = make(chan bool, 1)
	dataChannel.isStreamMessageResendTimeout = make(chan bool, 1)
	dataChannel.sessionType = ""
	dataChannel.IsAwsCliUpgradeNeeded = isAwsCliUpgradeNeeded
}

// SetWebsocket function populates websocket channel object
func (dataChannel *DataChannel) SetWebsocket(log log.T, channelUrl string, channelToken string) {
	dataChannel.wsChannel.Initialize(log, channelUrl, channelToken)
}

// FinalizeHandshake sends the token for service to acknowledge the connection.
func (dataChannel *DataChannel) FinalizeDataChannelHandshake(log log.T, tokenValue string) (err error) {
	uuid.SwitchFormat(uuid.CleanHyphen)
	uid := uuid.NewV4().String()

	log.Infof("Sending token through data channel %s to acknowledge connection", dataChannel.wsChannel.GetStreamUrl())
	openDataChannelInput := service.OpenDataChannelInput{
		MessageSchemaVersion: aws.String(config.MessageSchemaVersion),
		RequestId:            aws.String(uid),
		TokenValue:           aws.String(tokenValue),
		ClientId:             aws.String(dataChannel.ClientId),
		ClientVersion:        aws.String(version.Version),
	}

	var openDataChannelInputBytes []byte

	if openDataChannelInputBytes, err = json.Marshal(openDataChannelInput); err != nil {
		log.Errorf("Error serializing openDataChannelInput: %s", err)
		return
	}
	return dataChannel.SendMessage(log, openDataChannelInputBytes, websocket.TextMessage)
}

// SendMessage sends a message to the service through datachannel
func (dataChannel *DataChannel) SendMessage(log log.T, input []byte, inputType int) error {
	return dataChannel.wsChannel.SendMessage(log, input, inputType)
}

// Open opens websocket connects and does final handshake to acknowledge connection
func (dataChannel *DataChannel) Open(log log.T) (err error) {
	if err = dataChannel.wsChannel.Open(log); err != nil {
		return fmt.Errorf("failed to open data channel with error: %v", err)
	}

	if err = dataChannel.FinalizeDataChannelHandshake(log, dataChannel.wsChannel.GetChannelToken()); err != nil {
		return fmt.Errorf("error sending token for handshake: %v", err)
	}
	return
}

// Close closes datachannel - its web socket connection
func (dataChannel *DataChannel) Close(log log.T) error {
	log.Infof("Closing datachannel with url %s", dataChannel.wsChannel.GetStreamUrl())
	return dataChannel.wsChannel.Close(log)
}

// Reconnect calls ResumeSession API to reconnect datachannel when connection is lost
func (dataChannel *DataChannel) Reconnect(log log.T) (err error) {

	if err = dataChannel.Close(log); err != nil {
		log.Debugf("Closing datachannel failed with error: %v", err)
	}

	if err = dataChannel.Open(log); err != nil {
		return fmt.Errorf("failed to reconnect data channel %s with error: %v", dataChannel.wsChannel.GetStreamUrl(), err)
	}

	log.Infof("Successfully reconnected to data channel: %s", dataChannel.wsChannel.GetStreamUrl())
	return
}

// SendFlag sends a data message with PayloadType as given flag.
func (dataChannel *DataChannel) SendFlag(
	log log.T,
	flagType message.PayloadTypeFlag) (err error) {

	flagBuf := new(bytes.Buffer)
	binary.Write(flagBuf, binary.BigEndian, flagType)
	return dataChannel.SendInputDataMessage(log, message.Flag, flagBuf.Bytes())
}

// SendInputDataMessage sends a data message in a form of ClientMessage.
func (dataChannel *DataChannel) SendInputDataMessage(
	log log.T,
	payloadType message.PayloadType,
	inputData []byte) (err error) {

	var (
		flag uint64 = 0
		msg  []byte
	)

	messageId := uuid.NewV4()

	// today 'enter' is taken as 'next line' in winpty shell. so hardcoding 'next line' byte to actual 'enter' byte
	if bytes.Equal(inputData, []byte{10}) {
		inputData = []byte{13}
	}

	// Encrypt if encryption is enabled and payload type is Output
	if dataChannel.encryptionEnabled && payloadType == message.Output {
		inputData, err = dataChannel.encryption.Encrypt(log, inputData)
		if err != nil {
			return err
		}
	}

	clientMessage := message.ClientMessage{
		MessageType:    message.InputStreamMessage,
		SchemaVersion:  1,
		CreatedDate:    uint64(time.Now().UnixNano() / 1000000),
		Flags:          flag,
		MessageId:      messageId,
		PayloadType:    uint32(payloadType),
		Payload:        inputData,
		SequenceNumber: dataChannel.StreamDataSequenceNumber,
	}

	if msg, err = clientMessage.SerializeClientMessage(log); err != nil {
		log.Errorf("Cannot serialize StreamData message with error: %v", err)
		return
	}

	log.Tracef("Sending message with seq number: %d", dataChannel.StreamDataSequenceNumber)
	if err = SendMessageCall(log, dataChannel, msg, websocket.BinaryMessage); err != nil {
		log.Errorf("Error sending stream data message %v", err)
		return
	}

	streamingMessage := StreamingMessage{
		msg,
		dataChannel.StreamDataSequenceNumber,
		time.Now(),
		new(int),
	}
	dataChannel.AddDataToOutgoingMessageBuffer(streamingMessage)
	dataChannel.StreamDataSequenceNumber = dataChannel.StreamDataSequenceNumber + 1

	return
}

// ResendStreamDataMessageScheduler spawns a separate go thread which keeps checking OutgoingMessageBuffer at fixed interval
// and resends first message if time elapsed since lastSentTime of the message is more than acknowledge wait time
func (dataChannel *DataChannel) ResendStreamDataMessageScheduler(log log.T) (err error) {
	go func() {
		for {
			time.Sleep(config.ResendSleepInterval)
			dataChannel.OutgoingMessageBuffer.Mutex.Lock()
			streamMessageElement := dataChannel.OutgoingMessageBuffer.Messages.Front()
			dataChannel.OutgoingMessageBuffer.Mutex.Unlock()

			if streamMessageElement == nil {
				continue
			}

			streamMessage := streamMessageElement.Value.(StreamingMessage)
			if time.Since(streamMessage.LastSentTime) > dataChannel.RetransmissionTimeout {
				log.Debugf("Resend stream data message %d for the %d attempt.", streamMessage.SequenceNumber, *streamMessage.ResendAttempt)
				if *streamMessage.ResendAttempt >= config.ResendMaxAttempt {
					log.Warnf("Message %d was resent over %d times.", streamMessage.SequenceNumber, config.ResendMaxAttempt)
					dataChannel.isStreamMessageResendTimeout <- true
				}
				*streamMessage.ResendAttempt++
				if err = SendMessageCall(log, dataChannel, streamMessage.Content, websocket.BinaryMessage); err != nil {
					log.Errorf("Unable to send stream data message: %s", err)
				}
				streamMessage.LastSentTime = time.Now()
			}
		}
	}()

	return
}

// ProcessAcknowledgedMessage processes acknowledge messages by deleting them from OutgoingMessageBuffer
func (dataChannel *DataChannel) ProcessAcknowledgedMessage(log log.T, acknowledgeMessageContent message.AcknowledgeContent) error {
	acknowledgeSequenceNumber := acknowledgeMessageContent.SequenceNumber
	for streamMessageElement := dataChannel.OutgoingMessageBuffer.Messages.Front(); streamMessageElement != nil; streamMessageElement = streamMessageElement.Next() {
		streamMessage := streamMessageElement.Value.(StreamingMessage)
		if streamMessage.SequenceNumber == acknowledgeSequenceNumber {

			//Calculate retransmission timeout based on latest round trip time of message
			dataChannel.CalculateRetransmissionTimeout(log, streamMessage)

			dataChannel.RemoveDataFromOutgoingMessageBuffer(streamMessageElement)
			break
		}
	}
	return nil
}

// SendAcknowledgeMessage sends acknowledge message for stream data over data channel
func (dataChannel *DataChannel) SendAcknowledgeMessage(log log.T, streamDataMessage message.ClientMessage) (err error) {
	dataStreamAcknowledgeContent := message.AcknowledgeContent{
		MessageType:         streamDataMessage.MessageType,
		MessageId:           streamDataMessage.MessageId.String(),
		SequenceNumber:      streamDataMessage.SequenceNumber,
		IsSequentialMessage: true,
	}

	var msg []byte
	if msg, err = message.SerializeClientMessageWithAcknowledgeContent(log, dataStreamAcknowledgeContent); err != nil {
		log.Errorf("Cannot serialize Acknowledge message err: %v", err)
		return
	}

	if err = SendMessageCall(log, dataChannel, msg, websocket.BinaryMessage); err != nil {
		log.Errorf("Error sending acknowledge message %v", err)
		return
	}
	return
}

// OutputMessageHandler gets output on the data channel
func (dataChannel *DataChannel) OutputMessageHandler(log log.T, stopHandler Stop, sessionID string, rawMessage []byte) error {
	outputMessage := &message.ClientMessage{}
	err := outputMessage.DeserializeClientMessage(log, rawMessage)
	if err != nil {
		log.Errorf("Cannot deserialize raw message: %s, err: %v.", string(rawMessage), err)
		return err
	}
	if err = outputMessage.Validate(); err != nil {
		log.Errorf("Invalid outputMessage: %v, err: %v.", *outputMessage, err)
		return err
	}

	log.Tracef("Processing stream data message of type: %s", outputMessage.MessageType)
	switch outputMessage.MessageType {
	case message.OutputStreamMessage:
		return dataChannel.HandleOutputMessage(log, *outputMessage, rawMessage)
	case message.AcknowledgeMessage:
		return dataChannel.HandleAcknowledgeMessage(log, *outputMessage)
	case message.ChannelClosedMessage:
		dataChannel.HandleChannelClosedMessage(log, stopHandler, sessionID, *outputMessage)
	case message.StartPublicationMessage, message.PausePublicationMessage:
		return nil
	default:
		log.Warn("Invalid message type received: %s", outputMessage.MessageType)
	}

	return nil
}

// handleHandshakeRequest is the handler for payloads of type HandshakeRequest
func (dataChannel *DataChannel) handleHandshakeRequest(log log.T, clientMessage message.ClientMessage) error {

	handshakeRequest, err := clientMessage.DeserializeHandshakeRequest(log)
	if err != nil {
		log.Errorf("Deserialize Handshake Request failed: %s", err)
		return err
	}

	dataChannel.agentVersion = handshakeRequest.AgentVersion

	var errorList []error
	var handshakeResponse message.HandshakeResponsePayload
	handshakeResponse.ClientVersion = version.Version
	handshakeResponse.ProcessedClientActions = []message.ProcessedClientAction{}
	for _, action := range handshakeRequest.RequestedClientActions {
		processedAction := message.ProcessedClientAction{}
		switch action.ActionType {
		case message.KMSEncryption:
			processedAction.ActionType = action.ActionType
			err := dataChannel.ProcessKMSEncryptionHandshakeAction(log, action.ActionParameters)
			if err != nil {
				processedAction.ActionStatus = message.Failed
				processedAction.Error = fmt.Sprintf("Failed to process action %s: %s",
					message.KMSEncryption, err)
				errorList = append(errorList, err)
			} else {
				processedAction.ActionStatus = message.Success
				processedAction.ActionResult = message.KMSEncryptionResponse{
					KMSCipherTextKey: dataChannel.encryption.GetEncryptedDataKey(),
				}
				dataChannel.encryptionEnabled = true
			}
		case message.SessionType:
			processedAction.ActionType = action.ActionType
			err := dataChannel.ProcessSessionTypeHandshakeAction(action.ActionParameters)
			if err != nil {
				processedAction.ActionStatus = message.Failed
				processedAction.Error = fmt.Sprintf("Failed to process action %s: %s",
					message.SessionType, err)
				errorList = append(errorList, err)
			} else {
				processedAction.ActionStatus = message.Success
			}

		default:
			processedAction.ActionType = action.ActionType
			processedAction.ActionResult = message.Unsupported
			processedAction.Error = fmt.Sprintf("Unsupported action %s", action.ActionType)
			errorList = append(errorList, errors.New(processedAction.Error))
		}
		handshakeResponse.ProcessedClientActions = append(handshakeResponse.ProcessedClientActions, processedAction)
	}
	for _, x := range errorList {
		handshakeResponse.Errors = append(handshakeResponse.Errors, x.Error())
	}
	err = dataChannel.sendHandshakeResponse(log, handshakeResponse)
	return err
}

// handleHandshakeComplete is the handler for when the payload type is HandshakeComplete. This will trigger
// the plugin to start.
func (dataChannel *DataChannel) handleHandshakeComplete(log log.T, clientMessage message.ClientMessage) error {
	var err error
	var handshakeComplete message.HandshakeCompletePayload
	handshakeComplete, err = clientMessage.DeserializeHandshakeComplete(log)
	if err != nil {
		return err
	}

	// SessionType would be set when handshake request is received
	if dataChannel.sessionType != "" {
		dataChannel.isSessionTypeSet <- true
	} else {
		dataChannel.isSessionTypeSet <- false
	}

	log.Debugf("Handshake Complete. Handshake time to complete is: %s seconds",
		handshakeComplete.HandshakeTimeToComplete.Seconds())

	if handshakeComplete.CustomerMessage != "" {
		fmt.Fprintln(os.Stdout, handshakeComplete.CustomerMessage)
	}

	return err
}

// handleEncryptionChallengeRequest receives EncryptionChallenge and responds.
func (dataChannel *DataChannel) handleEncryptionChallengeRequest(log log.T, clientMessage message.ClientMessage) error {
	var err error
	var encChallengeReq message.EncryptionChallengeRequest
	err = json.Unmarshal(clientMessage.Payload, &encChallengeReq)
	if err != nil {
		return fmt.Errorf("Could not deserialize rawMessage, %s : %s", clientMessage.Payload, err)
	}
	challenge := encChallengeReq.Challenge
	challenge, err = dataChannel.encryption.Decrypt(log, challenge)
	if err != nil {
		return err
	}
	challenge, err = dataChannel.encryption.Encrypt(log, challenge)
	if err != nil {
		return err
	}
	encChallengeResp := message.EncryptionChallengeResponse{
		Challenge: challenge,
	}

	err = dataChannel.sendEncryptionChallengeResponse(log, encChallengeResp)
	return err
}

// sendEncryptionChallengeResponse sends EncryptionChallengeResponse
func (dataChannel *DataChannel) sendEncryptionChallengeResponse(log log.T, response message.EncryptionChallengeResponse) error {
	var resultBytes, err = json.Marshal(response)
	if err != nil {
		return fmt.Errorf("Could not serialize EncChallengeResponse message: %v, err: %s", response, err)
	}

	log.Tracef("Sending EncChallengeResponse message.")
	if err := dataChannel.SendInputDataMessage(log, message.EncChallengeResponse, resultBytes); err != nil {
		return err
	}
	return nil

}

// sendHandshakeResponse sends HandshakeResponse
func (dataChannel *DataChannel) sendHandshakeResponse(log log.T, response message.HandshakeResponsePayload) error {

	var resultBytes, err = json.Marshal(response)
	if err != nil {
		log.Errorf("Could not serialize HandshakeResponse message: %v, err: %s", response, err)
	}

	log.Tracef("Sending HandshakeResponse message.")
	if err := dataChannel.SendInputDataMessage(log, message.HandshakeResponsePayloadType, resultBytes); err != nil {
		return err
	}
	return nil
}

// RegisterOutputStreamHandler register a handler for messages of type OutputStream. This is usually called by the plugin.
func (dataChannel *DataChannel) RegisterOutputStreamHandler(handler OutputStreamDataMessageHandler, isSessionSpecificHandler bool) {
	dataChannel.isSessionSpecificHandlerSet = isSessionSpecificHandler
	dataChannel.outputStreamHandlers = append(dataChannel.outputStreamHandlers, handler)
}

// DeregisterOutputStreamHandler deregisters a handler previously registered using RegisterOutputStreamHandler
func (dataChannel *DataChannel) DeregisterOutputStreamHandler(handler OutputStreamDataMessageHandler) {
	// Find and remove "handler"
	for i, v := range dataChannel.outputStreamHandlers {
		if reflect.ValueOf(v).Pointer() == reflect.ValueOf(handler).Pointer() {
			dataChannel.outputStreamHandlers = append(dataChannel.outputStreamHandlers[:i], dataChannel.outputStreamHandlers[i+1:]...)
			break
		}
	}
}

func (dataChannel *DataChannel) processOutputMessageWithHandlers(log log.T, message message.ClientMessage) (isHandlerReady bool, err error) {
	// Return false if sessionType is known but session specific handler is not set
	if dataChannel.sessionType != "" && !dataChannel.isSessionSpecificHandlerSet {
		return false, nil
	}
	for _, handler := range dataChannel.outputStreamHandlers {
		isHandlerReady, err = handler(log, message)
		// Break the processing of message and return if session specific handler is not ready
		if err != nil || !isHandlerReady {
			break
		}
	}
	return isHandlerReady, err
}

// handleOutputMessage handles incoming stream data message by processing the payload and updating expectedSequenceNumber
func (dataChannel *DataChannel) HandleOutputMessage(
	log log.T,
	outputMessage message.ClientMessage,
	rawMessage []byte) (err error) {

	// On receiving expected stream data message, send acknowledgement, process it and increment expected sequence number by 1.
	// Further process messages from IncomingMessageBuffer
	if outputMessage.SequenceNumber == dataChannel.ExpectedSequenceNumber {

		switch message.PayloadType(outputMessage.PayloadType) {
		case message.HandshakeRequestPayloadType:
			{
				if err = SendAcknowledgeMessageCall(log, dataChannel, outputMessage); err != nil {
					return err
				}

				// PayloadType is HandshakeRequest so we call our own handler instead of the provided handler
				log.Debugf("Processing HandshakeRequest message %s", outputMessage)
				if err = dataChannel.handleHandshakeRequest(log, outputMessage); err != nil {
					log.Errorf("Unable to process incoming data payload, MessageType %s, "+
						"PayloadType HandshakeRequestPayloadType, err: %s.", outputMessage.MessageType, err)
					return err
				}
			}
		case message.HandshakeCompletePayloadType:
			{
				if err = SendAcknowledgeMessageCall(log, dataChannel, outputMessage); err != nil {
					return err
				}

				if err = dataChannel.handleHandshakeComplete(log, outputMessage); err != nil {
					log.Errorf("Unable to process incoming data payload, MessageType %s, "+
						"PayloadType HandshakeCompletePayloadType, err: %s.", outputMessage.MessageType, err)
					return err
				}
			}
		case message.EncChallengeRequest:
			{
				if err = SendAcknowledgeMessageCall(log, dataChannel, outputMessage); err != nil {
					return err
				}

				if err = dataChannel.handleEncryptionChallengeRequest(log, outputMessage); err != nil {
					log.Errorf("Unable to process incoming data payload, MessageType %s, "+
						"PayloadType EncChallengeRequest, err: %s.", outputMessage.MessageType, err)
					return err
				}
			}
		default:

			log.Tracef("Process new incoming stream data message. Sequence Number: %d", outputMessage.SequenceNumber)

			// Decrypt if encryption is enabled and payload type is output
			if dataChannel.encryptionEnabled &&
				(outputMessage.PayloadType == uint32(message.Output) ||
					outputMessage.PayloadType == uint32(message.StdErr) ||
					outputMessage.PayloadType == uint32(message.ExitCode)) {
				outputMessage.Payload, err = dataChannel.encryption.Decrypt(log, outputMessage.Payload)
				if err != nil {
					log.Errorf("Unable to decrypt incoming data payload, MessageType %s, "+
						"PayloadType %d, err: %s.", outputMessage.MessageType, outputMessage.PayloadType, err)
					return err
				}
			}

			isHandlerReady, err := dataChannel.processOutputMessageWithHandlers(log, outputMessage)
			if err != nil {
				log.Error("Failed to process stream data message: %s", err.Error())
				return err
			}
			if !isHandlerReady {
				log.Warnf("Stream data message with sequence number %d is not processed as session handler is not ready.", outputMessage.SequenceNumber)
				return nil
			} else {
				// Acknowledge outputMessage only if session specific handler is ready
				if err := SendAcknowledgeMessageCall(log, dataChannel, outputMessage); err != nil {
					return err
				}
			}
		}
		dataChannel.ExpectedSequenceNumber = dataChannel.ExpectedSequenceNumber + 1
		return dataChannel.ProcessIncomingMessageBufferItems(log, outputMessage)
	} else {
		log.Debugf("Unexpected sequence message received. Received Sequence Number: %d. Expected Sequence Number: %d",
			outputMessage.SequenceNumber, dataChannel.ExpectedSequenceNumber)

		// If incoming message sequence number is greater then expected sequence number and IncomingMessageBuffer has capacity,
		// add message to IncomingMessageBuffer and send acknowledgement
		if outputMessage.SequenceNumber > dataChannel.ExpectedSequenceNumber {
			log.Debugf("Received Sequence Number %d is higher than Expected Sequence Number %d, adding to IncomingMessageBuffer",
				outputMessage.SequenceNumber, dataChannel.ExpectedSequenceNumber)
			if len(dataChannel.IncomingMessageBuffer.Messages) < dataChannel.IncomingMessageBuffer.Capacity {
				if err = SendAcknowledgeMessageCall(log, dataChannel, outputMessage); err != nil {
					return err
				}

				streamingMessage := StreamingMessage{
					rawMessage,
					outputMessage.SequenceNumber,
					time.Now(),
					new(int),
				}

				//Add message to buffer for future processing
				dataChannel.AddDataToIncomingMessageBuffer(streamingMessage)
			}
		}
	}
	return nil
}

// processIncomingMessageBufferItems check if new expected sequence stream data is present in IncomingMessageBuffer.
// If so process it and increment expected sequence number.
// Repeat until expected sequence stream data is not found in IncomingMessageBuffer.
func (dataChannel *DataChannel) ProcessIncomingMessageBufferItems(log log.T,
	outputMessage message.ClientMessage) (err error) {

	for {
		bufferedStreamMessage := dataChannel.IncomingMessageBuffer.Messages[dataChannel.ExpectedSequenceNumber]
		if bufferedStreamMessage.Content != nil {
			log.Debugf("Process stream data message from IncomingMessageBuffer. "+
				"Sequence Number: %d", bufferedStreamMessage.SequenceNumber)

			if err := outputMessage.DeserializeClientMessage(log, bufferedStreamMessage.Content); err != nil {
				log.Errorf("Cannot deserialize raw message with err: %v.", err)
				return err
			}

			// Decrypt if encryption is enabled and payload type is output
			if dataChannel.encryptionEnabled &&
				(outputMessage.PayloadType == uint32(message.Output) ||
					outputMessage.PayloadType == uint32(message.StdErr) ||
					outputMessage.PayloadType == uint32(message.ExitCode)) {
				outputMessage.Payload, err = dataChannel.encryption.Decrypt(log, outputMessage.Payload)
				if err != nil {
					log.Errorf("Unable to decrypt buffered message data payload, MessageType %s, "+
						"PayloadType %d, err: %s.", outputMessage.MessageType, outputMessage.PayloadType, err)
					return err
				}
			}

			dataChannel.processOutputMessageWithHandlers(log, outputMessage)

			dataChannel.ExpectedSequenceNumber = dataChannel.ExpectedSequenceNumber + 1
			dataChannel.RemoveDataFromIncomingMessageBuffer(bufferedStreamMessage.SequenceNumber)
		} else {
			break
		}
	}
	return
}

// handleAcknowledgeMessage deserialize acknowledge content and process it
func (dataChannel *DataChannel) HandleAcknowledgeMessage(
	log log.T,
	outputMessage message.ClientMessage) (err error) {

	var acknowledgeMessage message.AcknowledgeContent
	if acknowledgeMessage, err = outputMessage.DeserializeDataStreamAcknowledgeContent(log); err != nil {
		log.Errorf("Cannot deserialize payload to AcknowledgeMessage with error: %v.", err)
		return err
	}

	err = ProcessAcknowledgedMessageCall(log, dataChannel, acknowledgeMessage)
	return err
}

// handleChannelClosedMessage exits the shell
func (dataChannel DataChannel) HandleChannelClosedMessage(log log.T, stopHandler Stop, sessionId string, outputMessage message.ClientMessage) {
	var (
		channelClosedMessage message.ChannelClosed
		err                  error
	)
	if channelClosedMessage, err = outputMessage.DeserializeChannelClosedMessage(log); err != nil {
		log.Errorf("Cannot deserialize payload to ChannelClosedMessage: %v.", err)
	}

	log.Infof("Exiting session with sessionId: %s with output: %s", sessionId, channelClosedMessage.Output)
	if channelClosedMessage.Output == "" {
		fmt.Fprintf(os.Stdout, "\n\nExiting session with sessionId: %s.\n\n", sessionId)
	} else {
		fmt.Fprintf(os.Stdout, "\n\nSessionId: %s : %s\n\n", sessionId, channelClosedMessage.Output)
	}

	stopHandler()
}

// AddDataToOutgoingMessageBuffer removes first message from OutgoingMessageBuffer if capacity is full and adds given message at the end
func (dataChannel *DataChannel) AddDataToOutgoingMessageBuffer(streamMessage StreamingMessage) {
	if dataChannel.OutgoingMessageBuffer.Messages.Len() == dataChannel.OutgoingMessageBuffer.Capacity {
		dataChannel.RemoveDataFromOutgoingMessageBuffer(dataChannel.OutgoingMessageBuffer.Messages.Front())
	}
	dataChannel.OutgoingMessageBuffer.Mutex.Lock()
	dataChannel.OutgoingMessageBuffer.Messages.PushBack(streamMessage)
	dataChannel.OutgoingMessageBuffer.Mutex.Unlock()
}

// RemoveDataFromOutgoingMessageBuffer removes given element from OutgoingMessageBuffer
func (dataChannel *DataChannel) RemoveDataFromOutgoingMessageBuffer(streamMessageElement *list.Element) {
	dataChannel.OutgoingMessageBuffer.Mutex.Lock()
	dataChannel.OutgoingMessageBuffer.Messages.Remove(streamMessageElement)
	dataChannel.OutgoingMessageBuffer.Mutex.Unlock()
}

// AddDataToIncomingMessageBuffer adds given message to IncomingMessageBuffer if it has capacity
func (dataChannel *DataChannel) AddDataToIncomingMessageBuffer(streamMessage StreamingMessage) {
	if len(dataChannel.IncomingMessageBuffer.Messages) == dataChannel.IncomingMessageBuffer.Capacity {
		return
	}
	dataChannel.IncomingMessageBuffer.Mutex.Lock()
	dataChannel.IncomingMessageBuffer.Messages[streamMessage.SequenceNumber] = streamMessage
	dataChannel.IncomingMessageBuffer.Mutex.Unlock()
}

// RemoveDataFromIncomingMessageBuffer removes given sequence number message from IncomingMessageBuffer
func (dataChannel *DataChannel) RemoveDataFromIncomingMessageBuffer(sequenceNumber int64) {
	dataChannel.IncomingMessageBuffer.Mutex.Lock()
	delete(dataChannel.IncomingMessageBuffer.Messages, sequenceNumber)
	dataChannel.IncomingMessageBuffer.Mutex.Unlock()
}

// CalculateRetransmissionTimeout calculates message retransmission timeout value based on round trip time on given message
func (dataChannel *DataChannel) CalculateRetransmissionTimeout(log log.T, streamingMessage StreamingMessage) {
	newRoundTripTime := float64(GetRoundTripTime(streamingMessage))

	dataChannel.RoundTripTimeVariation = ((1 - config.RTTVConstant) * dataChannel.RoundTripTimeVariation) +
		(config.RTTVConstant * math.Abs(dataChannel.RoundTripTime-newRoundTripTime))

	dataChannel.RoundTripTime = ((1 - config.RTTConstant) * dataChannel.RoundTripTime) +
		(config.RTTConstant * newRoundTripTime)

	dataChannel.RetransmissionTimeout = time.Duration(dataChannel.RoundTripTime +
		math.Max(float64(config.ClockGranularity), float64(4*dataChannel.RoundTripTimeVariation)))

	// Ensure RetransmissionTimeout do not exceed maximum timeout defined
	if dataChannel.RetransmissionTimeout > config.MaxTransmissionTimeout {
		dataChannel.RetransmissionTimeout = config.MaxTransmissionTimeout
	}
}

// ProcessKMSEncryptionHandshakeAction sets up the encrypter and calls KMS to generate a new data key. This is triggered
// when encryption is specified in HandshakeRequest
func (dataChannel *DataChannel) ProcessKMSEncryptionHandshakeAction(log log.T, actionParams json.RawMessage) (err error) {

	if dataChannel.IsAwsCliUpgradeNeeded {
		return errors.New("Installed version of CLI does not support Session Manager encryption feature. Please upgrade to the latest version of your CLI (e.g., AWS CLI).")
	}
	kmsEncRequest := message.KMSEncryptionRequest{}
	json.Unmarshal(actionParams, &kmsEncRequest)
	log.Info(actionParams)
	kmsKeyId := kmsEncRequest.KMSKeyID

	kmsService, err := encryption.NewKMSService(log)
	if err != nil {
		return fmt.Errorf("error while creating new KMS service, %v", err)
	}

	encryptionContext := map[string]*string{"aws:ssm:SessionId": &dataChannel.SessionId, "aws:ssm:TargetId": &dataChannel.TargetId}
	dataChannel.encryption, err = newEncrypter(log, kmsKeyId, encryptionContext, kmsService)
	return
}

// ProcessSessionTypeHandshakeAction processes session type action in HandshakeRequest. This sets the session type in the datachannel.
func (dataChannel *DataChannel) ProcessSessionTypeHandshakeAction(actionParams json.RawMessage) (err error) {
	sessTypeReq := message.SessionTypeRequest{}
	json.Unmarshal(actionParams, &sessTypeReq)
	switch sessTypeReq.SessionType {
	// This switch-case is just so that we can fail early if an unknown session type is passed in.
	case config.ShellPluginName, config.InteractiveCommandsPluginName, config.NonInteractiveCommandsPluginName:
		dataChannel.sessionType = config.ShellPluginName
		dataChannel.sessionProperties = sessTypeReq.Properties
		return nil
	case config.PortPluginName:
		dataChannel.sessionType = sessTypeReq.SessionType
		dataChannel.sessionProperties = sessTypeReq.Properties
		return nil
	default:
		return errors.New(fmt.Sprintf("Unknown session type %s", sessTypeReq.SessionType))
	}
}

// IsSessionTypeSet check has data channel sessionType been set
func (dataChannel *DataChannel) IsSessionTypeSet() chan bool {
	return dataChannel.isSessionTypeSet
}

// IsStreamMessageResendTimeout checks if resending a streaming message reaches timeout
func (dataChannel *DataChannel) IsStreamMessageResendTimeout() chan bool {
	return dataChannel.isStreamMessageResendTimeout
}

// SetSessionType set session type
func (dataChannel *DataChannel) SetSessionType(sessionType string) {
	dataChannel.sessionType = sessionType
	dataChannel.isSessionTypeSet <- true
}

// GetSessionType returns SessionType of the dataChannel
func (dataChannel *DataChannel) GetSessionType() string {
	return dataChannel.sessionType
}

// GetSessionProperties returns SessionProperties of the dataChannel
func (dataChannel *DataChannel) GetSessionProperties() interface{} {
	return dataChannel.sessionProperties
}

// GetWsChannel returns WsChannel of the dataChannel
func (dataChannel *DataChannel) GetWsChannel() communicator.IWebSocketChannel {
	return dataChannel.wsChannel
}

// SetWsChannel set WsChannel of the dataChannel
func (dataChannel *DataChannel) SetWsChannel(wsChannel communicator.IWebSocketChannel) {
	dataChannel.wsChannel = wsChannel
}

// GetStreamDataSequenceNumber returns StreamDataSequenceNumber of the dataChannel
func (dataChannel *DataChannel) GetStreamDataSequenceNumber() int64 {
	return dataChannel.StreamDataSequenceNumber
}

// GetAgentVersion returns agent version of the target instance
func (dataChannel *DataChannel) GetAgentVersion() string {
	return dataChannel.agentVersion
}

// SetAgentVersion set agent version of the target instance
func (dataChannel *DataChannel) SetAgentVersion(agentVersion string) {
	dataChannel.agentVersion = agentVersion
}
