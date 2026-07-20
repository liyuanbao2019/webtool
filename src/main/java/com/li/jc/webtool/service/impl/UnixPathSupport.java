package com.li.jc.webtool.service.impl;

import java.util.ArrayDeque;
import java.util.Deque;

/** Small Unix-path helper used by terminal cwd tracking and SFTP script lookup. */
final class UnixPathSupport {

    private UnixPathSupport() {
    }

    static String defaultHome(String user) {
        if (user == null || user.trim().isEmpty()) {
            return "/";
        }
        return "root".equalsIgnoreCase(user.trim()) ? "/root" : "/home/" + user.trim();
    }

    static String basename(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String value = path.trim();
        int separator = value.lastIndexOf('/');
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String value = path.trim().replace('\\', '/');
        boolean absolute = value.startsWith("/");
        Deque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.pollLast();
                }
            } else {
                parts.addLast(part);
            }
        }
        StringBuilder normalized = new StringBuilder(absolute ? "/" : "");
        for (String part : parts) {
            if (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) != '/') {
                normalized.append('/');
            }
            normalized.append(part);
        }
        if (normalized.length() == 0) {
            return absolute ? "/" : ".";
        }
        return normalized.toString();
    }

    static String parent(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int separator = normalized.lastIndexOf('/');
        return separator <= 0 ? "/" : normalized.substring(0, separator);
    }
}
