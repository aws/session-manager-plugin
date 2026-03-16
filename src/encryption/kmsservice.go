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
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/service/kms"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/sdkutil"
)

// KMSKeySizeInBytes is the key size that is fetched from KMS. 64 bytes key is split into two halves.
// First half 32 bytes key is used by agent for encryption and second half 32 bytes by clients like cli/console
const KMSKeySizeInBytes int32 = 64

// KMSAPI defines the interface for KMS operations
// This allows for easier testing and mocking
type KMSAPI interface {
	Decrypt(ctx context.Context, params *kms.DecryptInput, optFns ...func(*kms.Options)) (*kms.DecryptOutput, error)
	GenerateDataKey(ctx context.Context, params *kms.GenerateDataKeyInput, optFns ...func(*kms.Options)) (*kms.GenerateDataKeyOutput, error)
}

func NewKMSService(log log.T) (*kms.Client, error) {
	ctx := context.Background()
	cfg, err := sdkutil.GetDefaultConfig(ctx)
	if err != nil {
		return nil, err
	}

	kmsClient := kms.NewFromConfig(cfg)
	return kmsClient, nil
}

func KMSDecrypt(log log.T, svc KMSAPI, cipherTextBlob []byte, encryptionContext map[string]string) (plainText []byte, err error) {
	ctx := context.Background()

	output, err := svc.Decrypt(ctx, &kms.DecryptInput{
		CiphertextBlob:    cipherTextBlob,
		EncryptionContext: encryptionContext,
	})
	if err != nil {
		log.Error("Error when decrypting data key", err)
		return nil, err
	}
	return output.Plaintext, nil
}

// KMSGenerateDataKey gets cipher text and plain text keys from KMS service
func KMSGenerateDataKey(kmsKeyId string, svc KMSAPI, encryptionContext map[string]string) (cipherTextKey []byte, plainTextKey []byte, err error) {
	ctx := context.Background()
	kmsKeySize := KMSKeySizeInBytes

	generateDataKeyInput := kms.GenerateDataKeyInput{
		KeyId:             &kmsKeyId,
		NumberOfBytes:     &kmsKeySize,
		EncryptionContext: encryptionContext,
	}

	generateDataKeyOutput, err := svc.GenerateDataKey(ctx, &generateDataKeyInput)
	if err != nil {
		return nil, nil, fmt.Errorf("Error calling KMS GenerateDataKey API: %s", err)
	}

	return generateDataKeyOutput.CiphertextBlob, generateDataKeyOutput.Plaintext, nil
}
