package com.hdsl.account;

import java.io.IOException;

interface SecretProtector {
    byte[] protect(byte[] plaintext) throws IOException;
    byte[] unprotect(byte[] ciphertext) throws IOException;
}
