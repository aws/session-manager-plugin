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
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"

	"github.com/aws/session-manager-plugin/src/log"
	"github.com/gorilla/websocket"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

var (
	mockLogger          = log.NewMockLog()
	defaultChannelToken = "channelToken"
	defaultStreamUrl    = "streamUrl"
	defaultError        = errors.New("Default Error")
	defaultMessage      = []byte("Default Message")
)

type ErrorCallbackWrapper struct {
	mock.Mock
	err error
}

func (mock *ErrorCallbackWrapper) defaultErrorHandler(err error) {
	mock.Called(err)
	mock.err = err
}

type MessageCallbackWrapper struct {
	mock.Mock
	message []byte
}

func (mock *MessageCallbackWrapper) defaultMessageHandler(msg []byte) {
	mock.Called(msg)
	mock.message = msg
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
}

// handlerToBeTested echos all incoming input from a websocket connection back to the client while
// adding the word "echo".
func handlerToBeTested(w http.ResponseWriter, req *http.Request) {
	var log = log.NewMockLog()
	conn, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		http.Error(w, fmt.Sprintf("cannot upgrade: %v", err), http.StatusInternalServerError)
	}

	for {
		mt, p, err := conn.ReadMessage()

		if err != nil {
			log.Errorf("error: %v", err)
			return
		}

		//echo back the same sent string from the client while adding "echo" at the beginning
		conn.WriteMessage(mt, []byte("echo "+string(p)))
	}
}

func TestWebSocketChannel_GetChannelToken(t *testing.T) {
	t.Log("Starting test: webSocketChannel.GetChannelToken")
	channel := &WebSocketChannel{
		ChannelToken: defaultChannelToken,
	}

	token := channel.GetChannelToken()
	assert.Equal(t, defaultChannelToken, token)
}

func TestWebSocketChannel_SetChannelToken(t *testing.T) {
	t.Log("Starting test: webSocketChannel.SetChannelToken")
	channel := &WebSocketChannel{}

	channel.SetChannelToken(defaultChannelToken)
	assert.Equal(t, defaultChannelToken, channel.ChannelToken)
}

func TestWebSocketChannel_GetStreamUrl(t *testing.T) {
	t.Log("Starting test: webSocketChannel.GetStreamUrl")
	channel := &WebSocketChannel{
		Url: defaultStreamUrl,
	}

	url := channel.GetStreamUrl()
	assert.Equal(t, defaultStreamUrl, url)
}

func TestWebSocketChannel_SetOnError(t *testing.T) {
	t.Log("Starting test: webSocketChannel.SetOnError")
	channel := &WebSocketChannel{}
	errorCallbackWrapper := &ErrorCallbackWrapper{}
	errorCallbackWrapper.On("defaultErrorHandler", defaultError).Return()

	channel.SetOnError((*errorCallbackWrapper).defaultErrorHandler)
	channel.OnError(defaultError)

	errorCallbackWrapper.AssertCalled(t, "defaultErrorHandler", defaultError)
	assert.Equal(t, defaultError.Error(), errorCallbackWrapper.err.Error())
}

func TestWebsocketChannel_SetOnMessage(t *testing.T) {
	t.Log("Starting test: webSocketChannel.SetOnMessage")
	channel := &WebSocketChannel{}
	messageCallbackWrapper := &MessageCallbackWrapper{}
	messageCallbackWrapper.On("defaultMessageHandler", defaultMessage).Return()

	channel.SetOnMessage((*messageCallbackWrapper).defaultMessageHandler)
	channel.OnMessage(defaultMessage)

	messageCallbackWrapper.AssertCalled(t, "defaultMessageHandler", defaultMessage)
	assert.Equal(t, defaultMessage, messageCallbackWrapper.message)
}

func TestWebsocketchannel_Initialize(t *testing.T) {
	t.Log("Starting test: webSocketChannel.Initialize")
	channel := &WebSocketChannel{}
	channel.Initialize(mockLogger, defaultStreamUrl, defaultChannelToken)

	assert.Equal(t, defaultStreamUrl, channel.Url)
	assert.Equal(t, defaultChannelToken, channel.ChannelToken)
}

func TestOpenCloseWebSocketChannel(t *testing.T) {
	t.Log("Starting test: TestOpenCloseWebSocketChannel")
	srv := httptest.NewServer(http.HandlerFunc(handlerToBeTested))
	u, _ := url.Parse(srv.URL)
	u.Scheme = "ws"
	var log = log.NewMockLog()

	websocketchannel := WebSocketChannel{
		Url: u.String(),
	}

	err := websocketchannel.Open(log)
	assert.Nil(t, err, "Error opening the websocket connection.")
	assert.NotNil(t, websocketchannel.Connection, "Open connection failed.")
	assert.True(t, websocketchannel.IsOpen, "IsOpen is not set to true.")

	err = websocketchannel.Close(log)
	assert.Nil(t, err, "Error closing the websocket connection.")
	assert.False(t, websocketchannel.IsOpen, "IsOpen is not set to false.")
	t.Log("Ending test: TestOpenCloseWebSocketChannel")
}

func TestReadWriteTextToWebSocketChannel(t *testing.T) {
	t.Log("Starting test: TestReadWriteWebSocketChannel ")
	srv := httptest.NewServer(http.HandlerFunc(handlerToBeTested))
	u, _ := url.Parse(srv.URL)
	u.Scheme = "ws"
	var log = log.NewMockLog()

	var wg sync.WaitGroup
	wg.Add(1)

	onMessage := func(input []byte) {
		defer wg.Done()
		t.Log(input)
		// Verify read from websocket server
		assert.Equal(t, string(input), "echo channelreadwrite")
	}

	websocketchannel := WebSocketChannel{
		Url:       u.String(),
		OnMessage: onMessage,
	}

	// Open the websocket connection
	err := websocketchannel.Open(log)
	assert.Nil(t, err, "Error opening the websocket connection.")
	assert.NotNil(t, websocketchannel.Connection, "Open connection failed.")

	// Verify write to websocket server
	websocketchannel.SendMessage(log, []byte("channelreadwrite"), websocket.TextMessage)
	wg.Wait()

	err = websocketchannel.Close(log)
	assert.Nil(t, err, "Error closing the websocket connection.")
	assert.False(t, websocketchannel.IsOpen, "IsOpen is not set to false.")
	t.Log("Ending test: TestReadWriteWebSocketChannel ")
}

func TestReadWriteBinaryToWebSocketChannel(t *testing.T) {
	t.Log("Starting test: TestReadWriteWebSocketChannel ")
	srv := httptest.NewServer(http.HandlerFunc(handlerToBeTested))
	u, _ := url.Parse(srv.URL)
	u.Scheme = "ws"
	var log = log.NewMockLog()

	var wg sync.WaitGroup
	wg.Add(1)

	onMessage := func(input []byte) {
		defer wg.Done()
		t.Log(input)
		// Verify read from websocket server
		assert.Equal(t, string(input), "echo channelreadwrite")
	}

	websocketchannel := WebSocketChannel{
		Url:       u.String(),
		OnMessage: onMessage,
	}

	// Open the websocket connection
	err := websocketchannel.Open(log)
	assert.Nil(t, err, "Error opening the websocket connection.")
	assert.NotNil(t, websocketchannel.Connection, "Open connection failed.")

	// Verify write to websocket server
	websocketchannel.SendMessage(log, []byte("channelreadwrite"), websocket.BinaryMessage)
	wg.Wait()

	err = websocketchannel.Close(log)
	assert.Nil(t, err, "Error closing the websocket connection.")
	assert.False(t, websocketchannel.IsOpen, "IsOpen is not set to false.")
	t.Log("Ending test: TestReadWriteWebSocketChannel ")
}

func TestMultipleReadWriteWebSocketChannel(t *testing.T) {
	t.Log("Starting test: TestMultipleReadWriteWebSocketChannel")
	srv := httptest.NewServer(http.HandlerFunc(handlerToBeTested))
	u, _ := url.Parse(srv.URL)
	u.Scheme = "ws"
	var log = log.NewMockLog()

	read1 := make(chan bool)
	read2 := make(chan bool)

	onMessage := func(input []byte) {
		t.Log(input)
		// Verify reads from websocket server
		if string(input) == "echo channelreadwrite1" {
			read1 <- true
		}
		if string(input) == "echo channelreadwrite2" {
			read2 <- true
		}
	}

	websocketchannel := WebSocketChannel{
		Url:       u.String(),
		OnMessage: onMessage,
	}

	// Open the websocket connection
	err := websocketchannel.Open(log)
	assert.Nil(t, err, "Error opening the websocket connection.")
	assert.NotNil(t, websocketchannel.Connection, "Open connection failed.")

	// Verify writes to websocket server
	websocketchannel.SendMessage(log, []byte("channelreadwrite1"), websocket.TextMessage)
	websocketchannel.SendMessage(log, []byte("channelreadwrite2"), websocket.TextMessage)
	assert.True(t, <-read1, "Didn't read value 1 correctly")
	assert.True(t, <-read2, "Didn't ready value 2 correctly")

	err = websocketchannel.Close(log)
	assert.Nil(t, err, "Error closing the websocket connection.")
	assert.False(t, websocketchannel.IsOpen, "IsOpen is not set to false.")

	t.Log("Ending test: TestMultipleReadWriteWebSocketChannel")
}
