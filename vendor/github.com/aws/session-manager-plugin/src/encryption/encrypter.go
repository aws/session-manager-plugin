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
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"fmt"
	"io"

	"github.com/aws/aws-sdk-go/service/kms/kmsiface"
	"github.com/aws/session-manager-plugin/src/log"
)

const (
	nonceSize = 12
)

type KMSKeyProvider interface {
	GenerateDataKey()
}

type IEncrypter interface {
	Encrypt(log log.T, plainText []byte) (cipherText []byte, err error)
	Decrypt(log log.T, cipherText []byte) (plainText []byte, err error)
	GetEncryptedDataKey() (ciptherTextBlob []byte)
}

type Encrypter struct {
	KMSService kmsiface.KMSAPI

	kmsKeyId      string
	cipherTextKey []byte
	encryptionKey []byte
	decryptionKey []byte
}

var NewEncrypter = func(log log.T, kmsKeyId string, context map[string]*string, KMSService kmsiface.KMSAPI) (*Encrypter, error) {
	encrypter := Encrypter{kmsKeyId: kmsKeyId, KMSService: KMSService}
	err := encrypter.generateEncryptionKey(log, kmsKeyId, context)
	return &encrypter, err
}

// generateEncryptionKey calls KMS to generate a new encryption key
func (encrypter *Encrypter) generateEncryptionKey(log log.T, kmsKeyId string, context map[string]*string) error {
	cipherTextKey, plainTextKey, err := KMSGenerateDataKey(kmsKeyId, encrypter.KMSService, context)
	if err != nil {
		log.Errorf("Error generating data key from KMS: %s,", err)
		return err
	}
	keySize := len(plainTextKey) / 2
	encrypter.decryptionKey = plainTextKey[:keySize]
	encrypter.encryptionKey = plainTextKey[keySize:]
	encrypter.cipherTextKey = cipherTextKey
	return nil
}

// GetEncryptedDataKey returns the cipherText that was pulled from KMS
func (encrypter *Encrypter) GetEncryptedDataKey() (ciptherTextBlob []byte) {
	return encrypter.cipherTextKey
}

// GetKMSKeyId gets the KMS key id that is used to generate the encryption key
func (encrypter *Encrypter) GetKMSKeyId() (kmsKey string) {
	return encrypter.kmsKeyId
}

// getAEAD gets AEAD which is a GCM cipher mode providing authenticated encryption with associated data
func getAEAD(plainTextKey []byte) (aesgcm cipher.AEAD, err error) {
	var block cipher.Block
	if block, err = aes.NewCipher(plainTextKey); err != nil {
		return nil, fmt.Errorf("error creating NewCipher, %v", err)
	}

	if aesgcm, err = cipher.NewGCM(block); err != nil {
		return nil, fmt.Errorf("error creating NewGCM, %v", err)
	}

	return aesgcm, nil
}

// Encrypt encrypts a byte slice and returns the encrypted slice
func (encrypter *Encrypter) Encrypt(log log.T, plainText []byte) (cipherText []byte, err error) {
	var aesgcm cipher.AEAD

	if aesgcm, err = getAEAD(encrypter.encryptionKey); err != nil {
		err = fmt.Errorf("%v", err)
		return
	}

	cipherText = make([]byte, nonceSize+len(plainText))
	nonce := make([]byte, nonceSize)
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		err = fmt.Errorf("error when generating nonce for encryption, %v", err)
		return
	}

	// Encrypt plain text using given key and newly generated nonce
	cipherTextWithoutNonce := aesgcm.Seal(nil, nonce, plainText, nil)

	// Append nonce to the beginning of the cipher text to be used while decrypting
	cipherText = append(cipherText[:nonceSize], nonce...)
	cipherText = append(cipherText[nonceSize:], cipherTextWithoutNonce...)
	return cipherText, nil
}

// Decrypt decrypts a byte slice and returns the decrypted slice
func (encrypter *Encrypter) Decrypt(log log.T, cipherText []byte) (plainText []byte, err error) {
	var aesgcm cipher.AEAD
	if aesgcm, err = getAEAD(encrypter.decryptionKey); err != nil {
		err = fmt.Errorf("%v", err)
		return
	}

	// Pull the nonce out of the cipherText
	nonce := cipherText[:nonceSize]
	cipherTextWithoutNonce := cipherText[nonceSize:]

	// Decrypt just the actual cipherText using nonce extracted above
	if plainText, err = aesgcm.Open(nil, nonce, cipherTextWithoutNonce, nil); err != nil {
		err = fmt.Errorf("error decrypting encrypted test, %v", err)
		return
	}
	return plainText, nil
}
