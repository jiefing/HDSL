package com.hdsl.account;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;

/** Windows DPAPI CurrentUser scope, never the less restrictive machine scope. */
final class WindowsSecretProtector implements SecretProtector {
    private void requireWindows() throws IOException {
        if (!Platform.isWindows()) throw new IOException("统一 API 账户需要 Windows 当前用户的 DPAPI 加密服务。");
    }
    @Override public byte[] protect(byte[] plaintext) throws IOException {
        requireWindows();
        try { return Crypt32Util.cryptProtectData(plaintext); }
        catch (RuntimeException e) { throw new IOException("Windows 无法加密账户密钥。", e); }
    }
    @Override public byte[] unprotect(byte[] ciphertext) throws IOException {
        requireWindows();
        try { return Crypt32Util.cryptUnprotectData(ciphertext); }
        catch (RuntimeException e) { throw new IOException("无法解密账户密钥，请在当前 Windows 用户下重新保存此账户的 API Key。", e); }
    }
}
