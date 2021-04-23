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

// Package jsonutil contains various utilities for dealing with json data.
package jsonutil

import (
	"fmt"
	"io/ioutil"
	"log"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
)

func ExampleMarshal() {
	type ColorGroup struct {
		ID     int
		Name   string
		Colors []string
	}
	group := ColorGroup{
		ID:     1,
		Name:   "Reds",
		Colors: []string{"Crimson", "Red", "Ruby", "Maroon"},
	}
	b, err := Marshal(group)
	if err != nil {
		fmt.Println("error:", err)
	}
	fmt.Println(b)
	// Output:
	// {"ID":1,"Name":"Reds","Colors":["Crimson","Red","Ruby","Maroon"]}
}

func ExampleRemarshal() {
	type ColorGroup struct {
		ID     int
		Name   string
		Colors []string
	}
	group := ColorGroup{
		ID:     1,
		Name:   "Reds",
		Colors: []string{"Crimson", "Red", "Ruby", "Maroon"},
	}

	var newGroup ColorGroup

	err := Remarshal(group, &newGroup)
	if err != nil {
		fmt.Println("error:", err)
	}

	out, err := Marshal(newGroup)
	if err != nil {
		fmt.Println("error:", err)
	}

	fmt.Println(out)
	// Output:
	// {"ID":1,"Name":"Reds","Colors":["Crimson","Red","Ruby","Maroon"]}
}

func ExampleIndent() {
	type Road struct {
		Name   string
		Number int
	}
	roads := []Road{
		{"Diamond Fork", 29},
		{"Sheep Creek", 51},
	}

	b, err := Marshal(roads)
	if err != nil {
		log.Fatal(err)
	}

	out := Indent(b)
	fmt.Println(out)
	// Output:
	// [
	//   {
	//     "Name": "Diamond Fork",
	//     "Number": 29
	//   },
	//   {
	//     "Name": "Sheep Creek",
	//     "Number": 51
	//   }
	// ]
}

func TestIndent(t *testing.T) {
	testCases := []struct {
		name  string
		input string
	}{
		{"Basic", "[{\"Name\":\"Diamond Fork\", \"Number\":29}, {\"Name\":\"Sheep Creek\", \"Number\":51}]"},
		{"BasicMoreWhitespace", "[\n{\"Name\":\"Diamond Fork\",     \"Number\":29}, {    \"Name\"   :   \"Sheep Creek\",    \"Number\":51}]"},
	}
	for _, tc := range testCases {
		out := Indent(tc.input)
		correct, err := ioutil.ReadFile(filepath.Join("testdata", t.Name()+tc.name+".golden"))
		if err != nil {
			t.Errorf("error reading file: %v", err)
		}
		assert.Equal(t, string(correct), out)
	}
}

func TestMarshal(t *testing.T) {
	group := struct {
		ID     int
		Name   string
		Colors []string
	}{
		1,
		"Reds",
		[]string{"Crimson", "Red", "Ruby", "Maroon"},
	}
	out, err := Marshal(group)
	if err != nil {
		t.Errorf("error in %s: %v", t.Name(), err)
	}
	correct, err := ioutil.ReadFile(filepath.Join("testdata", t.Name()+".golden"))
	assert.Equal(t, string(correct), out)
}

func TestUnmarshalFile(t *testing.T) {
	filename := "rumpelstilzchen"
	var contents interface{}

	// missing file
	ioUtil = ioUtilStub{err: fmt.Errorf("some error")}
	err1 := UnmarshalFile(filename, &contents)
	assert.Error(t, err1, "expected readfile error")

	// non json content
	ioUtil = ioUtilStub{b: []byte("Sample text")}
	err2 := UnmarshalFile(filename, &contents)
	assert.Error(t, err2, "expected json parsing error")

	// valid json content
	ioUtil = ioUtilStub{b: []byte("{\"ID\":1,\"Name\":\"Reds\",\"Colors\":[\"Crimson\",\"Red\",\"Ruby\",\"Maroon\"]}")}
	err3 := UnmarshalFile(filename, &contents)
	assert.NoError(t, err3, "message should parse successfully")
}

func TestRemarshal(t *testing.T) {
	prop := make(map[string]string)
	prop["RunCommand"] = "echo"
	prop2 := make(map[string]string)
	prop2["command"] = "echo"
	type Property struct {
		RunCommand string
	}
	var newProp Property
	var newProp2 Property
	err := Remarshal(prop, &newProp)
	assert.NoError(t, err, "message should remarshal successfully")
	err = Remarshal(prop2, &newProp2)
	assert.NoError(t, err, "key mismatch should not report error")
	assert.Equal(t, Property{}, newProp2, "mismatched remarshal should return an empty object")
}

func TestRemarshalInvalidInput(t *testing.T) {
	// Using channel as unsupported json type
	// Expect an error and no change to input object
	badInput := make(chan bool)
	type Output struct {
		name string
	}
	var output Output
	// Save an copy of output to compare to after Remarshal has been called to confirm no changes were made
	copy := output
	err := Remarshal(badInput, &output)
	assert.NotNil(t, err)
	if !assert.ObjectsAreEqual(copy, output) {
		t.Fatalf("Object was modified by call to Remarshal")
	}
}

func TestUnmarshal(t *testing.T) {
	content := `{"parameter": "1"}`
	type TestStruct struct {
		Parameter string `json:"parameter"`
	}
	output := TestStruct{}
	err := Unmarshal(content, &output)
	assert.NoError(t, err, "Message should parse correctly")
	assert.Equal(t, output.Parameter, "1")
}

func TestUnmarshalExtraInput(t *testing.T) {
	content := `{"parameter": "1", "name": "Richard"}`
	type TestStruct struct {
		Parameter string `json:"parameter"`
	}
	output := TestStruct{}
	err := Unmarshal(content, &output)
	assert.NoError(t, err, "Message should parse correctly")
	assert.Equal(t, output.Parameter, "1")
}

func TestUnmarshalInvalidInput(t *testing.T) {
	content := "Hello"
	var dest interface{}
	err := Unmarshal(content, &dest)
	assert.Error(t, err, "This is not json format. Error expected")
}

func TestMarshalIndent(t *testing.T) {
	group := struct {
		ID     int
		Name   string
		Colors []string
	}{
		1,
		"Reds",
		[]string{"Crimson", "Red", "Ruby", "Maroon"},
	}
	correct, err := ioutil.ReadFile(filepath.Join("testdata", t.Name()+".golden"))
	if err != nil {
		t.Errorf("error: %v", err)
		t.FailNow()
	}
	out, err := MarshalIndent(group)
	if err != nil {
		t.Errorf("error: %v", err)
		t.FailNow()
	}
	assert.Equal(t, string(correct), out)
}

func TestMarshalIndentErrorsOnInvalidInput(t *testing.T) {
	// Using channel as invalid input
	// Breaks the same for any json-invalid types
	_, err := MarshalIndent(make(chan int))
	assert.NotNil(t, err)
}

// ioutil stub
type ioUtilStub struct {
	b   []byte
	err error
}

func (a ioUtilStub) ReadFile(_ string) ([]byte, error) {
	return a.b, a.err
}
