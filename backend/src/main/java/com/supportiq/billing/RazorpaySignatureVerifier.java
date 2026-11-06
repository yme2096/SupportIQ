package com.supportiq.billing;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Razorpay-compatible raw-body webhook signature verifier; unused while DEMO billing is configured. */
@Component
public class RazorpaySignatureVerifier {
    public boolean verify(String rawRequestBody,String suppliedSignature,String webhookSecret){
        if(rawRequestBody==null||suppliedSignature==null||webhookSecret==null||webhookSecret.isBlank())return false;
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] expected=HexFormat.of().formatHex(mac.doFinal(rawRequestBody.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);return MessageDigest.isEqual(expected,suppliedSignature.trim().getBytes(StandardCharsets.US_ASCII));}
        catch(Exception e){return false;}
    }
}
