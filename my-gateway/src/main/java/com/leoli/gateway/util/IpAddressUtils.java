package com.leoli.gateway.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Literal-only address parsing. Never resolves a hostname on the request path. */
public final class IpAddressUtils {
    private IpAddressUtils() { }

    private record Network(byte[] address, int prefix) { }
    private static final Network INVALID = new Network(null, 0);
    private static final Cache<String, Network> NETWORKS = Caffeine.newBuilder().maximumSize(1024).build();

    private static byte[] bytes(String value) {
        if (value == null || value.isBlank()) return null;
        byte[] address = NetUtil.createByteArrayFromIpAddressString(value.trim());
        if (address != null && address.length == 16) {
            boolean mapped = address[10] == (byte) 0xff && address[11] == (byte) 0xff;
            for (int i = 0; i < 10 && mapped; i++) mapped = address[i] == 0;
            if (mapped) return Arrays.copyOfRange(address, 12, 16);
        }
        return address;
    }

    public static String normalize(String value) {
        byte[] address = bytes(value);
        if (address == null) return null;
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    public static boolean matches(String ip, String range) {
        byte[] address = bytes(ip);
        if (address == null || range == null || range.isBlank()) return false;
        Network network = NETWORKS.get(range.trim(), IpAddressUtils::parseNetwork);
        if (network.address() == null || address.length != network.address().length) return false;
        int wholeBytes = network.prefix() / 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (address[i] != network.address()[i]) return false;
        }
        int bits = network.prefix() % 8;
        int mask = (0xff << (8 - bits)) & 0xff;
        return bits == 0 || (address[wholeBytes] & mask) == (network.address()[wholeBytes] & mask);
    }

    private static Network parseNetwork(String value) {
        String[] parts = value.split("/", -1);
        if (parts.length > 2) return INVALID;
        byte[] address = bytes(parts[0]);
        if (address == null) return INVALID;
        try {
            int prefix = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
            if (parts.length == 2 && address.length == 4 && parts[0].contains(":")) {
                if (prefix < 96 || prefix > 128) return INVALID;
                prefix -= 96;
            }
            if (prefix < 0 || prefix > address.length * 8) return INVALID;
            return new Network(address, prefix);
        } catch (NumberFormatException invalid) {
            return INVALID;
        }
    }
}
