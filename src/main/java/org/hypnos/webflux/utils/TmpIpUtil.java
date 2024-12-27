package org.hypnos.webflux.utils;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

public class TmpIpUtil {

    public static final String IP = "((\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])\\.){3}(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])";

    public static final String PORT = "(\\d|[1-9]\\d{1,3}|[1-5]\\d{4}|6[0-4]\\d{4}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])";

    public static final String IP_PORT = "^" + IP + ":" + PORT + "$";

    public static final Pattern IP_PORT_REGEXP = Pattern.compile(IP_PORT);

    private TmpIpUtil() {

    }


    public static String getIp(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String iP = headers.getFirst("X-Real-IP");
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            iP = headers.getFirst("X-Forwarded-For");
        }
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            iP = headers.getFirst("Proxy-Client-IP");
        }
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            iP = headers.getFirst("WL-Proxy-Client-IP");
        }
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            iP = headers.getFirst("HTTP_CLIENT_IP");
        }
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            iP = headers.getFirst("HTTP_X_FORWARDED_FOR");
        }
        if (!StringUtils.hasLength(iP) || isUnknown(iP)) {
            InetSocketAddress remoteAddress = request.getRemoteAddress();
            if (remoteAddress != null) {
                iP = remoteAddress.getAddress().getHostAddress();
            }
        }
        if (isLocalHost(iP)) {
            iP = getLocalHostIp();
        }
        if (!StringUtils.hasLength(iP)) {
            iP = "unknown";
        }
        return iP;
    }

    public static String getServerAddr() {
        return getLocalHostIp();
    }

    private static boolean isUnknown(String iP) {
        return "unknown".equalsIgnoreCase(iP);
    }

    private static boolean isLocalHost(String iP) {
        return "127.0.0.1".equals(iP) || "localhost".equalsIgnoreCase(iP);
    }

    private static String getLocalHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isValid(String iPAndPort) {
        return IP_PORT_REGEXP.matcher(iPAndPort).matches();
    }

}
