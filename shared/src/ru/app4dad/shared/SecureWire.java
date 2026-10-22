package ru.app4dad.shared;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Authenticated encryption over a local HTTP transport. No code or file names in plaintext. */
public final class SecureWire {
    private static final byte[] AAD="App4Dad/v1".getBytes(StandardCharsets.UTF_8);
    public static String newCode() { byte[] key=new byte[32]; new SecureRandom().nextBytes(key); return Base64.getUrlEncoder().withoutPadding().encodeToString(key); }
    public static byte[] key(String code) {
        byte[] key=Base64.getUrlDecoder().decode(code.trim());
        if(key.length!=32) throw new IllegalArgumentException("Код сопряжения должен содержать 43 символа");
        return key;
    }
    public static byte[] encrypt(Properties message,byte[] key) throws Exception {
        StringWriter text=new StringWriter(); message.store(text,null);
        byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); cipher.updateAAD(AAD);
        byte[] encrypted=cipher.doFinal(text.toString().getBytes(StandardCharsets.UTF_8));
        byte[] packet=new byte[nonce.length+encrypted.length];
        System.arraycopy(nonce,0,packet,0,12); System.arraycopy(encrypted,0,packet,12,encrypted.length);
        return packet;
    }
    public static Properties decrypt(byte[] packet,byte[] key) throws Exception {
        if(packet.length<28) throw new GeneralSecurityException("Короткий пакет");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,packet,0,12)); cipher.updateAAD(AAD);
        byte[] plain=cipher.doFinal(packet,12,packet.length-12);
        Properties message=new Properties(); message.load(new StringReader(new String(plain,StandardCharsets.UTF_8))); return message;
    }
    public static Properties request(String action) {
        Properties p=new Properties(); p.setProperty("action",action); p.setProperty("id",UUID.randomUUID().toString());
        p.setProperty("time",Long.toString(System.currentTimeMillis())); return p;
    }
    private SecureWire() {}
}
