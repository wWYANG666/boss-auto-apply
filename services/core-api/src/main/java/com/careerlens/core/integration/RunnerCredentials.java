package com.careerlens.core.integration;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class RunnerCredentials {
    private final JdbcTemplate jdbc;
    private final String secret;
    public RunnerCredentials(JdbcTemplate jdbc, @Value("${careerlens.runner.encryption-key:}") String secret) {
        this.jdbc=jdbc;this.secret=secret;
    }
    public void requireConfigured() {
        if (secret.length()<32) throw ApiException.conflict("RUNNER_KEY_REQUIRED",
                "请先设置至少32字符的RUNNER_ENCRYPTION_KEY，再配对设备");
    }
    public UUID currentOwner() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth!=null && auth.getPrincipal() instanceof AppPrincipal user) return user.id();
        return null;
    }
    public Optional<String> currentToken() {
        UUID owner=currentOwner();
        if(owner==null)return Optional.empty();
        List<String> values=jdbc.query("SELECT encrypted_token FROM runner_device_binding WHERE user_id=?",
                (rs,n)->rs.getString(1),owner);
        return values.stream().findFirst().map(this::decrypt);
    }
    public void save(UUID owner,String token) {
        requireConfigured();
        String encrypted=encrypt(token);
        int updated=jdbc.update("UPDATE runner_device_binding SET encrypted_token=?, paired_at=CURRENT_TIMESTAMP WHERE user_id=?",encrypted,owner);
        if(updated==0)jdbc.update("INSERT INTO runner_device_binding (user_id,encrypted_token,paired_at) VALUES (?,?,CURRENT_TIMESTAMP)",owner,encrypted);
    }
    public String stageRotation(UUID owner) {
        requireConfigured();
        List<String> pending=jdbc.query("SELECT encrypted_pending_token FROM runner_device_binding WHERE user_id=?",(rs,n)->rs.getString(1),owner);
        if(pending.isEmpty())throw ApiException.conflict("RUNNER_NOT_PAIRED","请先配对设备");
        if(pending.get(0)!=null)return decrypt(pending.get(0));
        String token=com.careerlens.core.common.Hashing.randomToken();
        jdbc.update("UPDATE runner_device_binding SET encrypted_pending_token=? WHERE user_id=?",encrypt(token),owner);
        return token;
    }
    public void completeRotation(UUID owner) {jdbc.update("UPDATE runner_device_binding SET encrypted_pending_token=NULL WHERE user_id=?",owner);}
    public void forget(UUID owner) {
        jdbc.update("DELETE FROM runner_device_binding WHERE user_id=?",owner);
        jdbc.update("UPDATE platform_account SET connection_status='disconnected' WHERE user_id=?",owner);
        jdbc.update("UPDATE platform_identity SET connection_status='disconnected' WHERE user_id=?",owner);
        jdbc.update("UPDATE platform_execution_policy SET paused=TRUE WHERE user_id=?",owner);
    }
    String encrypt(String value) {
        requireConfigured();
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all=Arrays.copyOf(iv,iv.length+ciphertext.length);
            System.arraycopy(ciphertext,0,all,iv.length,ciphertext.length);
            return Base64.getEncoder().encodeToString(all);
        }catch(Exception e){throw new IllegalStateException("Runner credential encryption failed",e);}
    }
    String decrypt(String value) {
        requireConfigured();
        try {
            byte[] all=Base64.getDecoder().decode(value);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(all,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8);
        }catch(Exception e){throw ApiException.conflict("RUNNER_KEY_CHANGED","设备凭据解密失败，请恢复原加密密钥或重新配对");}
    }
    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),"AES");
    }
}
