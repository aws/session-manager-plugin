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

// Package log is used to initialize the logger.
package log

import (
	"sync"

	"github.com/cihub/seelog"
)

const (
	LogFileExtension     = ".log"
	SeelogConfigFileName = "seelog.xml"
	ErrorLogFileSuffix   = "errors"
)

var (
	err                         error
	DefaultSeelogConfigFilePath string // DefaultSeelogConfigFilePath specifies the default seelog location
	DefaultLogDir               string // DefaultLogDir specifies default log location
	ApplicationLogFile          string // ApplicationLogFile specifies name of application log file
	ErrorLogFile                string // ErrorLogFile specifies name of error log file
	loadedLogger                *T
	lock                        sync.RWMutex
)

// pkgMutex is the lock used to serialize calls to the logger.
var pkgMutex = new(sync.Mutex)

// loggerInstance is the delegate logger in the wrapper
var loggerInstance = &DelegateLogger{}

// ContextFormatFilter is a filter that can add a context to the parameters of a log message.
type ContextFormatFilter struct {
	Context []string
}

type LogConfig struct {
	ClientName string
}

// Filter adds the context at the beginning of the parameter slice.
func (f ContextFormatFilter) Filter(params ...interface{}) (newParams []interface{}) {
	newParams = make([]interface{}, len(f.Context)+len(params))
	for i, param := range f.Context {
		newParams[i] = param + " "
	}
	ctxLen := len(f.Context)
	for i, param := range params {
		newParams[ctxLen+i] = param
	}
	return newParams
}

// Filterf adds the context in from of the format string.
func (f ContextFormatFilter) Filterf(format string, params ...interface{}) (newFormat string, newParams []interface{}) {
	newFormat = ""
	for _, param := range f.Context {
		newFormat += param + " "
	}
	newFormat += format
	newParams = params
	return
}

// Logger is the starting point to initialize with client name.
func Logger(useWatcher bool, clientName string) T {
	logConfig := LogConfig{
		ClientName: clientName,
	}
	if !isLoaded() {
		logger := logConfig.InitLogger(useWatcher)
		cache(logger)
	}
	return getCached()
}

// initLogger initializes a new logger based on current configurations and starts file watcher on the configurations file
func (config *LogConfig) InitLogger(useWatcher bool) (logger T) {
	// Read the current configurations or get the default configurations
	logConfigBytes := config.GetLogConfigBytes()
	// Initialize the base seelog logger
	baseLogger, _ := initBaseLoggerFromBytes(logConfigBytes)
	// Create the wrapper logger
	logger = withContext(baseLogger)
	if useWatcher {
		// Start the config file watcher
		config.startWatcher(logger)
	}
	return
}

// check if a logger has be loaded
func isLoaded() bool {
	lock.RLock()
	defer lock.RUnlock()
	return loadedLogger != nil
}

// cache the loaded logger
func cache(logger T) {
	lock.Lock()
	defer lock.Unlock()
	loadedLogger = &logger
}

// return the cached logger
func getCached() T {
	lock.RLock()
	defer lock.RUnlock()
	return *loadedLogger
}

// startWatcher starts the file watcher on the seelog configurations file path
func (config *LogConfig) startWatcher(logger T) {
	defer func() {
		// In case the creation of watcher panics, let the current logger continue
		if msg := recover(); msg != nil {
			logger.Errorf("Seelog File Watcher Initilization Failed. Any updates on config file will be ignored unless agent is restarted: %v", msg)
		}
	}()
	fileWatcher := &FileWatcher{}
	fileWatcher.Init(logger, DefaultSeelogConfigFilePath, config.replaceLogger)
	// Start the file watcher
	fileWatcher.Start()
}

// ReplaceLogger replaces the current logger with a new logger initialized from the current configurations file
func (config *LogConfig) replaceLogger() {

	// Get the current logger
	logger := getCached()

	//Create new logger
	logConfigBytes := config.GetLogConfigBytes()
	baseLogger, err := initBaseLoggerFromBytes(logConfigBytes)

	// If err in creating logger, do not replace logger
	if err != nil {
		logger.Error("New logger creation failed")
		return
	}

	setStackDepth(baseLogger)
	baseLogger.Debug("New Logger Successfully Created")

	// Safe conversion to *Wrapper
	wrapper, ok := logger.(*Wrapper)
	if !ok {
		logger.Errorf("Logger replace failed. The logger is not a wrapper")
		return
	}

	// Replace the underlying base logger in wrapper
	wrapper.ReplaceDelegate(baseLogger)
}

func (config *LogConfig) GetLogConfigBytes() []byte {
	return getLogConfigBytes(config.ClientName)
}

// initBaseLoggerFromBytes initializes the base logger using the specified configuration as bytes.
func initBaseLoggerFromBytes(seelogConfig []byte) (seelogger seelog.LoggerInterface, err error) {
	seelogger, err = seelog.LoggerFromConfigAsBytes(seelogConfig)
	if err != nil {
		// Create logger with default config
		seelogger, _ = seelog.LoggerFromConfigAsBytes(DefaultConfig())
	}
	return
}

// withContext creates a wrapper logger on the base logger passed with context is passed
func withContext(logger seelog.LoggerInterface, context ...string) (contextLogger T) {
	loggerInstance.BaseLoggerInstance = logger
	formatFilter := &ContextFormatFilter{Context: context}
	contextLogger = &Wrapper{Format: formatFilter, M: pkgMutex, Delegate: loggerInstance}

	setStackDepth(logger)
	return contextLogger
}

// setStackDepth sets the stack depth of the logger passed
func setStackDepth(logger seelog.LoggerInterface) {
	// additional stack depth so that we print the calling function correctly
	// stack depth 0 would print the function in the wrapper (e.g. wrapper.Debug)
	// stack depth 1 prints the function calling the logger (wrapper), which is what we want.
	logger.SetAdditionalStackDepth(1)
}
