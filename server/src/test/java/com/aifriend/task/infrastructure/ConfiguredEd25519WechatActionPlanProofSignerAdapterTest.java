package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.task.application.WechatActionPlanSigningProperties;

class ConfiguredEd25519WechatActionPlanProofSignerAdapterTest {

    @Test
    void configuredRfc8032KeyMustProduceVerifiableEd25519Signature() throws Exception {
        byte[] seed = HexFormat.of().parseHex(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] pkcs8 = join(HexFormat.of().parseHex("302e020100300506032b657004220420"), seed);
        byte[] rawPublic = HexFormat.of().parseHex(
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] x509 = join(HexFormat.of().parseHex("302a300506032b6570032100"), rawPublic);
        ConfiguredEd25519WechatActionPlanProofSignerAdapter signer =
                new ConfiguredEd25519WechatActionPlanProofSignerAdapter(
                        new WechatActionPlanSigningProperties(
                                "test-rfc8032",
                                Base64.getEncoder().encodeToString(pkcs8)));
        byte[] message = "cross-platform-canonical-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] signed = signer.sign(message);

        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(x509));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertTrue(verifier.verify(signed));
    }

    @Test
    void missingOrMalformedPrivateKeyMustFailClosed() {
        ConfiguredEd25519WechatActionPlanProofSignerAdapter missing =
                new ConfiguredEd25519WechatActionPlanProofSignerAdapter(
                        new WechatActionPlanSigningProperties("", ""));
        assertFalse(missing.available());
        assertThrows(BusinessException.class, () -> missing.sign(new byte[] {1}));

        ConfiguredEd25519WechatActionPlanProofSignerAdapter malformed =
                new ConfiguredEd25519WechatActionPlanProofSignerAdapter(
                        new WechatActionPlanSigningProperties("test-key", "not-base64"));
        assertTrue(malformed.available());
        assertThrows(BusinessException.class, () -> malformed.sign(new byte[] {1}));
    }

    private byte[] join(byte[] left, byte[] right) {
        byte[] joined = new byte[left.length + right.length];
        System.arraycopy(left, 0, joined, 0, left.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }
}
