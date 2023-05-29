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

// retry implements back off retry strategy for reconnect web socket connection.
package retry

import (
	"time"

	"github.com/aws/session-manager-plugin/src/log"
)

const sleepConstant = 2

// Retry implements back off retry strategy for reconnect web socket connection.
func Retry(log log.T, attempts int, sleep time.Duration, fn func() error) (err error) {

	log.Info("Retrying connection to channel")
	for attempts > 0 {
		attempts--
		if err = fn(); err != nil {
			time.Sleep(sleep)
			sleep = sleep * sleepConstant
			log.Debugf("%v attempts to connect web socket connection.", attempts)
			continue
		}
		return nil
	}
	return err
}
