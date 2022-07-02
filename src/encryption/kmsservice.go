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

package encryption

import (
	"fmt"

	sdkSession "github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/kms"
	"github.com/aws/aws-sdk-go/service/kms/kmsiface"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/sdkutil"
)

// KMSKeySizeInBytes is the key size that is fetched from KMS. 64 bytes key is split into two halves.
// First half 32 bytes key is used by agent for encryption and second half 32 bytes by clients like cli/console
const KMSKeySizeInBytes int64 = 64

func NewKMSService(log log.T) (kmsService *kms.KMS, err error) {
	var session *sdkSession.Session
	if session, err = sdkutil.GetDefaultSession(); err != nil {
		return nil, err
	}

	kmsService = kms.New(session)
	return kmsService, nil
}

func KMSDecrypt(log log.T, svc kmsiface.KMSAPI, ciptherTextBlob []byte, encryptionContext map[string]*string) (plainText []byte, err error) {
	output, err := svc.Decrypt(&kms.DecryptInput{
		CiphertextBlob:    ciptherTextBlob,
		EncryptionContext: encryptionContext})
	if err != nil {
		log.Error("Error when decrypting data key", err)
		return nil, err
	}
	return output.Plaintext, nil
}

// GenerateDataKey gets cipher text and plain text keys from KMS service
func KMSGenerateDataKey(kmsKeyId string, svc kmsiface.KMSAPI, context map[string]*string) (cipherTextKey []byte, plainTextKey []byte, err error) {
	kmsKeySize := KMSKeySizeInBytes
	generateDataKeyInput := kms.GenerateDataKeyInput{
		KeyId:             &kmsKeyId,
		NumberOfBytes:     &kmsKeySize,
		EncryptionContext: context,
	}

	var generateDataKeyOutput *kms.GenerateDataKeyOutput
	if generateDataKeyOutput, err = svc.GenerateDataKey(&generateDataKeyInput); err != nil {
		return nil, nil, fmt.Errorf("Error calling KMS GenerateDataKey API: %s", err)
	}

	return generateDataKeyOutput.CiphertextBlob, generateDataKeyOutput.Plaintext, nil
}
