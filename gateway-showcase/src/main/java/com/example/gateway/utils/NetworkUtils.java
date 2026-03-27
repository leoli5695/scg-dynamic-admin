package com.example.gateway.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Network Utility Functions
 * 
 * Provides common network-related utilities for IP address handling.
 * 
 * @author Your Name
 */
public final class NetworkUtils {
    
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    
    private static final Pattern IPV6_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"
    );
    
    private static final Pattern CIDR_PATTERN = Pattern.compile(
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])/([0-9]|[1-2][0-9]|3[0-2])$"
    );
    
    private NetworkUtils() {
        // Utility class, prevent instantiation
    }
    
    /**
     * Check if a string is a valid IPv4 address
     * 
     * @param ip The IP address string
     * @return true if valid IPv4, false otherwise
     */
    public static boolean isValidIPv4(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip).matches();
    }
    
    /**
     * Check if a string is a valid IPv6 address
     * 
     * @param ip The IP address string
     * @return true if valid IPv6, false otherwise
     */
    public static boolean isValidIPv6(String ip) {
        return ip != null && IPV6_PATTERN.matcher(ip).matches();
    }
    
    /**
     * Check if a string is a valid CIDR notation
     * 
     * @param cidr The CIDR notation string (e.g., "192.168.1.0/24")
     * @return true if valid CIDR, false otherwise
     */
    public static boolean isValidCIDR(String cidr) {
        return cidr != null && CIDR_PATTERN.matcher(cidr).matches();
    }
    
    /**
     * Check if an IP address is within a CIDR range
     * 
     * @param ip The IP address to check
     * @param cidr The CIDR range (e.g., "192.168.1.0/24")
     * @return true if IP is within range, false otherwise
     */
    public static boolean isInRange(String ip, String cidr) {
        if (!isValidIPv4(ip) || !isValidCIDR(cidr)) {
            return false;
        }
        
        String[] parts = cidr.split("/");
        String networkAddress = parts[0];
        int prefixLength = Integer.parseInt(parts[1]);
        
        long ipValue = ipToLong(ip);
        long networkValue = ipToLong(networkAddress);
        long mask = 0xFFFFFFFFL << (32 - prefixLength);
        
        return (ipValue & mask) == (networkValue & mask);
    }
    
    /**
     * Convert IP address to long value
     * 
     * @param ip The IP address string
     * @return The long representation
     */
    public static long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (Long.parseLong(octets[i]) << (24 - (8 * i)));
        }
        return result;
    }
    
    /**
     * Get local host IP address
     * 
     * @return Local IP address or "127.0.0.1" if unable to determine
     */
    public static String getLocalIp() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            return localHost.getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}