// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

// Package version contains CLI version constant and utilities.
package version

import (
	"fmt"
	"strconv"
	"strings"
)

type version struct {
	version []string
}

// NewVersion initializes version struct by splitting given version string into string list using separator "."
func NewVersion(versionString string) (version, error) {
	if versionString == "" {
		return version{}, fmt.Errorf("invalid version %s", versionString)
	}

	return version{
		strings.Split(versionString, "."),
	}, nil
}

// compare returns 0 if thisVersion is equal to otherVersion, 1 if thisVersion is greater than otherVersion, -1 otherwise
func (thisVersion version) compare(otherVersion version) (int, error) {
	if len(thisVersion.version) != len(otherVersion.version) {
		return -1, fmt.Errorf("length mismatch for versions %s and %s", thisVersion.version, otherVersion.version)
	}

	var (
		thisVersionSlice  int
		otherVersionSlice int
		err               error
	)
	for i := range thisVersion.version {
		if thisVersionSlice, err = strconv.Atoi(thisVersion.version[i]); err != nil {
			return -1, err
		}
		if otherVersionSlice, err = strconv.Atoi(otherVersion.version[i]); err != nil {
			return -1, err
		}

		if thisVersionSlice > otherVersionSlice {
			return 1, nil
		} else if thisVersionSlice < otherVersionSlice {
			return -1, nil
		}
	}
	return 0, nil
}
