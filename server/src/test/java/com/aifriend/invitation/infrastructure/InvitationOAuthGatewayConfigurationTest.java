package com.aifriend.invitation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class InvitationOAuthGatewayConfigurationTest {

    @Test
    void shouldProvidePerIpGatewayLimitAndExactPublicCallbackMapping() throws IOException {
        String global = read("deploy/openresty/00-ai-friend-oauth-http.conf.example");
        String site = read("deploy/openresty/api-ai-friend-oauth-location.conf.example");

        assertThat(global).contains(
                "limit_req_zone $binary_remote_addr",
                "zone=ai_friend_invitation_oauth_per_ip:10m rate=6r/m");
        assertThat(site).contains(
                "location = /api/v1/oauth/wechat/invitation-callback",
                "limit_req zone=ai_friend_invitation_oauth_per_ip burst=3 nodelay",
                "limit_req_status 429",
                "error_page 429 =303 https://api.ai-friend.asia/api/v1/invite/unavailable",
                "proxy_pass http://127.0.0.1:8080");
    }

    @Test
    void shouldDisableCallbackLogsThatCouldPersistCodeOrState() throws IOException {
        String site = read("deploy/openresty/api-ai-friend-oauth-location.conf.example");

        assertThat(site).contains("access_log off", "error_log /dev/null crit");
        assertThat(site).doesNotContain(
                "$request ", "$request_uri", "$args", "$arg_code", "$arg_state");
    }

    private String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
