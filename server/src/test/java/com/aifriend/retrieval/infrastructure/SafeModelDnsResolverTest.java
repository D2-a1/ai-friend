package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SafeModelDnsResolverTest {
    @Test void blocksPrivateReservedMappedAndTransitionAddresses() throws Exception {
        for (String address : new String[] {"0.0.0.0", "10.1.2.3", "127.0.0.1", "100.64.1.1", "169.254.1.2",
                "172.16.1.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255", "::1", "::", "fc00::1", "fe80::1", "::ffff:127.0.0.1",
                "64:ff9b::a00:1", "2001:db8::1", "2002:a00:1::1", "3fff::1"}) {
            assertThat(SafeModelDnsResolver.isPublic(InetAddress.getByName(address))).as(address).isFalse();
        }
        for (String address : new String[] {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"}) {
            assertThat(SafeModelDnsResolver.isPublic(InetAddress.getByName(address))).as(address).isTrue();
        }
    }

    @Test void onlyApprovedHostIsResolvedAndMixedAnswerIsRejected() throws Exception {
        var count = new AtomicInteger();
        var resolver = new SafeModelDnsResolver(Set.of("embed.vendor.net"), host -> {
            count.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
        });
        assertThatThrownBy(() -> resolver.resolve("attacker.net")).isInstanceOf(UnknownHostException.class);
        assertThat(count).hasValue(0);
        assertThatThrownBy(() -> resolver.resolve("embed.vendor.net")).isInstanceOf(UnknownHostException.class);
        assertThat(count).hasValue(1);
    }

    @Test void changedDnsAnswerCannotBypassConnectionTimeValidation() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new SafeModelDnsResolver(Set.of("embed.vendor.net"), host ->
                new InetAddress[] {InetAddress.getByName(calls.getAndIncrement() == 0 ? "8.8.8.8" : "127.0.0.1")});
        assertThat(resolver.resolve("embed.vendor.net")[0].getHostAddress()).isEqualTo("8.8.8.8");
        assertThatThrownBy(() -> resolver.resolve("embed.vendor.net")).isInstanceOf(UnknownHostException.class);
    }
}
