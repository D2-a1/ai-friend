package com.aifriend.task.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WechatActionPlanKeyGeneratorCliTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void shouldGenerateCompatibleServerAndAndroidConfiguration() throws Exception {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path output = Files.createDirectory(temporaryRoot.resolve("keys"));

        var generated = WechatActionPlanKeyGeneratorCli.generate(
                new WechatActionPlanKeyGeneratorCli.GenerationOptions(
                        repository,
                        output,
                        "0.0.1",
                        "8.0.76"));

        Map<String, String> serverEnvironment = Files.readAllLines(
                        generated.serverEnvironmentPath(), UTF_8)
                .stream()
                .collect(Collectors.toMap(
                        line -> line.substring(0, line.indexOf('=')),
                        line -> line.substring(line.indexOf('=') + 1)));
        String androidJson = Files.readString(generated.androidTrustPath(), UTF_8);
        String publicKeyBase64 = jsonValue(
                androidJson,
                "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64");
        String androidKeyId = jsonValue(
                androidJson,
                "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID");

        assertEquals("false", serverEnvironment.get(
                "AI_FRIEND_WECHAT_EXECUTION_ENABLED"));
        assertEquals(generated.keyId(), serverEnvironment.get(
                "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_ID"));
        assertEquals(generated.keyId(), androidKeyId);
        assertEquals("0.0.1", serverEnvironment.get(
                "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_APP_VERSION"));
        assertEquals("8.0.76", serverEnvironment.get(
                "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_WECHAT_VERSION"));
        assertEquals("wechat-semantic-call-v1", serverEnvironment.get(
                "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_RULE_VERSION"));

        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(serverEnvironment.get(
                        "AI_FRIEND_WECHAT_ACTION_PLAN_PRIVATE_KEY_PKCS8_BASE64"))));
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        byte[] payload = "cross-stack-proof".getBytes(UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);

        assertTrue(verifier.verify(signature));
        assertFalse(androidJson.contains(
                "AI_FRIEND_WECHAT_ACTION_PLAN_PRIVATE_KEY_PKCS8_BASE64"));
        assertFalse(androidJson.contains(serverEnvironment.get(
                "AI_FRIEND_WECHAT_ACTION_PLAN_PRIVATE_KEY_PKCS8_BASE64")));
        assertEquals(64, generated.publicKeySha256().length());
    }

    @Test
    void shouldRejectRepositoryOutputExistingFilesAndInvalidVersions() throws Exception {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path insideRepository = Files.createDirectory(repository.resolve("keys"));
        Path nonEmpty = Files.createDirectory(temporaryRoot.resolve("non-empty"));
        Files.writeString(nonEmpty.resolve("existing.txt"), "keep", UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                WechatActionPlanKeyGeneratorCli.generate(
                        new WechatActionPlanKeyGeneratorCli.GenerationOptions(
                                repository, insideRepository, "0.0.1", "8.0.76")));
        assertThrows(IllegalArgumentException.class, () ->
                WechatActionPlanKeyGeneratorCli.generate(
                        new WechatActionPlanKeyGeneratorCli.GenerationOptions(
                                repository, nonEmpty, "0.0.1", "8.0.76")));
        assertThrows(IllegalArgumentException.class, () ->
                WechatActionPlanKeyGeneratorCli.options(Map.of(
                        "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_REPOSITORY_ROOT",
                        repository.toString(),
                        "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_OUTPUT_DIRECTORY",
                        nonEmpty.toString(),
                        "AI_FRIEND_WECHAT_ACTION_PLAN_APP_VERSION",
                        "0.0.1:bad",
                        "AI_FRIEND_WECHAT_ACTION_PLAN_WECHAT_VERSION",
                        "8.0.76")));
    }

    private static String jsonValue(String json, String name) {
        String marker = "\"" + name + "\": \"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0);
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        assertTrue(end > valueStart);
        return json.substring(valueStart, end);
    }
}
