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

// this package implement base communicator for network connections.
package communicator

import (
	"errors"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go/aws/signer/v4"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/websocketutil"
	"github.com/gorilla/websocket"
)

// IWebSocketChannel is the interface for DataChannel.
type IWebSocketChannel interface {
	Initialize(log log.T, channelUrl string, channelToken string, region string, signer *v4.Signer)
	Open(log log.T) error
	Close(log log.T) error
	SendMessage(log log.T, input []byte, inputType int) error
	StartPings(log log.T, pingInterval time.Duration)
	GetChannelToken() string
	GetStreamUrl() string
	SetChannelToken(string)
	SetOnError(onErrorHandler func(error))
	SetOnMessage(onMessageHandler func([]byte))
}

// WebSocketChannel parent class for DataChannel.
type WebSocketChannel struct {
	IWebSocketChannel
	Url          string
	OnMessage    func([]byte)
	OnError      func(error)
	IsOpen       bool
	writeLock    *sync.Mutex
	Connection   *websocket.Conn
	ChannelToken string
	Region       string
	Signer       *v4.Signer
}

// GetChannelToken gets the channel token
func (webSocketChannel *WebSocketChannel) GetChannelToken() string {
	return webSocketChannel.ChannelToken
}

// SetChannelToken sets the channel token
func (webSocketChannel *WebSocketChannel) SetChannelToken(channelToken string) {
	webSocketChannel.ChannelToken = channelToken
}

// GetStreamUrl gets stream url
func (webSocketChannel *WebSocketChannel) GetStreamUrl() string {
	return webSocketChannel.Url
}

// SetOnError sets OnError field of websocket channel
func (webSocketChannel *WebSocketChannel) SetOnError(onErrorHandler func(error)) {
	webSocketChannel.OnError = onErrorHandler
}

// SetOnMessage sets OnMessage field of websocket channel
func (webSocketChannel *WebSocketChannel) SetOnMessage(onMessageHandler func([]byte)) {
	webSocketChannel.OnMessage = onMessageHandler
}

// Initialize initializes websocket channel fields
func (webSocketChannel *WebSocketChannel) Initialize(log log.T, channelUrl string, channelToken string, region string, signer *v4.Signer) {
	webSocketChannel.ChannelToken = channelToken
	webSocketChannel.Url = channelUrl
	webSocketChannel.Region = region
	webSocketChannel.Signer = signer
}

// StartPings starts the pinging process to keep the websocket channel alive.
func (webSocketChannel *WebSocketChannel) StartPings(log log.T, pingInterval time.Duration) {

	go func() {
		for {
			if webSocketChannel.IsOpen == false {
				return
			}

			log.Debug("WebsocketChannel: Send ping. Message.")
			webSocketChannel.writeLock.Lock()
			err := webSocketChannel.Connection.WriteMessage(websocket.PingMessage, []byte("keepalive"))
			webSocketChannel.writeLock.Unlock()
			if err != nil {
				log.Errorf("Error while sending websocket ping: %v", err)
				return
			}
			time.Sleep(pingInterval)
		}
	}()
}

// SendMessage sends a byte message through the websocket connection.
// Examples of message type are websocket.TextMessage or websocket.Binary
func (webSocketChannel *WebSocketChannel) SendMessage(log log.T, input []byte, inputType int) error {
	if webSocketChannel.IsOpen == false {
		return errors.New("Can't send message: Connection is closed.")
	}

	if len(input) < 1 {
		return errors.New("Can't send message: Empty input.")
	}

	webSocketChannel.writeLock.Lock()
	err := webSocketChannel.Connection.WriteMessage(inputType, input)
	webSocketChannel.writeLock.Unlock()
	return err
}

// getV4SignatureHeader gets the signed header.
func (webSocketChannel *WebSocketChannel) getV4SignatureHeader(log log.T, Url string) (http.Header, error) {
	request, err := http.NewRequest("GET", Url, nil)

	if webSocketChannel.Signer != nil {
		_, err = webSocketChannel.Signer.Sign(request, nil, config.ServiceName, webSocketChannel.Region, time.Now())
		if err != nil {
			log.Errorf("Failed to sign websocket, %v", err)
		}
	}
	return request.Header, err
}

// isPresignedURL check is the url presigned.
func isPresignedURL(rawURL string) (bool, error) {
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		return false, err
	}

	queryParams := parsedURL.Query()

	presignedURLParams := []string{
		"X-Amz-Algorithm",
		"X-Amz-Credential",
		"X-Amz-Date",
		"X-Amz-Expires",
		"X-Amz-SignedHeaders",
		"X-Amz-Signature",
		"X-Amz-Security-Token",
	}

	for _, param := range presignedURLParams {
		if _, exists := queryParams[param]; exists {
			return true, nil
		}
	}

	return false, nil
}

// Close closes the corresponding connection.
func (webSocketChannel *WebSocketChannel) Close(log log.T) error {

	log.Info("Closing websocket channel connection to: " + webSocketChannel.Url)
	if webSocketChannel.IsOpen == true {
		// Send signal to stop receiving message
		webSocketChannel.IsOpen = false
		return websocketutil.NewWebsocketUtil(log, nil).CloseConnection(webSocketChannel.Connection)
	}

	log.Info("Websocket channel connection to: " + webSocketChannel.Url + " is already Closed!")
	return nil
}

// Open upgrades the http connection to a websocket connection.
func (webSocketChannel *WebSocketChannel) Open(log log.T) error {
	// initialize the write mutex
	webSocketChannel.writeLock = &sync.Mutex{}
	presigned, err := isPresignedURL(webSocketChannel.Url)
	if err != nil {
		return err
	}

	var header http.Header
	if !presigned {
		header, err = webSocketChannel.getV4SignatureHeader(log, webSocketChannel.Url)
		if err != nil {
			log.Errorf("Failed to get the v4 signature, %v", err)
		}
	}

	ws, err := websocketutil.NewWebsocketUtil(log, nil).OpenConnection(webSocketChannel.Url, header)
	if err != nil {
		log.Errorf("Failed to open WebSocket connection: %v", err)
		return err
	}
	webSocketChannel.Connection = ws
	webSocketChannel.IsOpen = true
	webSocketChannel.StartPings(log, config.PingTimeInterval)

	// spin up a different routine to listen to the incoming traffic
	go func() {
		defer func() {
			if msg := recover(); msg != nil {
				log.Errorf("WebsocketChannel listener run panic: %v", msg)
			}
		}()

		retryCount := 0
		for {
			if webSocketChannel.IsOpen == false {
				log.Debugf("Ending the channel listening routine since the channel is closed: %s",
					webSocketChannel.Url)
				break
			}

			messageType, rawMessage, err := webSocketChannel.Connection.ReadMessage()
			if err != nil {
				retryCount++
				if retryCount >= config.RetryAttempt {
					log.Errorf("Reach the retry limit %v for receive messages.", config.RetryAttempt)
					webSocketChannel.OnError(err)
					break
				}
				log.Debugf("An error happened when receiving the message. Retried times: %v, Error: %v, Messagetype: %v",
					retryCount,
					err.Error(),
					messageType)
			} else if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
				// We only accept text messages which are interpreted as UTF-8 or binary encoded text.
				log.Errorf("Invalid message type. We only accept UTF-8 or binary encoded text. Message type: %v", messageType)

			} else {
				retryCount = 0
				webSocketChannel.OnMessage(rawMessage)
			}
		}
	}()
	return nil
}
