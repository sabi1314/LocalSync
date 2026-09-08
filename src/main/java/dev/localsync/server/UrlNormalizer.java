package dev.localsync.server;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UrlNormalizer {
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+",
        Pattern.CASE_INSENSITIVE);

    private UrlNormalizer() {
    }

    static String normalize(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        Matcher matcher = URL_IN_TEXT.matcher(input.trim());
        if (!matcher.find()) {
            return null;
        }
        String value = trimSharePunctuation(matcher.group());
        if (value.isEmpty() || value.length() > maxLength) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String trimSharePunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            if (last == ')' || last == ']' || last == '}' || last == '>'
                    || last == ',' || last == '.' || last == ';' || last == ':'
                    || last == '，' || last == '。' || last == '；' || last == '：'
                    || last == '！' || last == '？' || last == '\"' || last == '\'') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }
}
