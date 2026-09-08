package com.xetax.crm.whatsapp.service;

public interface SecretEncryptionService {

    String encrypt(String plainText);

    String decrypt(String cipherText);
}
