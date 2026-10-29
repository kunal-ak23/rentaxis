package com.datagami.rentaxis.core.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Promoter API key secrets (promoter developer API): {@code mfk_live_} followed by 32
 * SecureRandom bytes in base62, always 43 characters (52 in all). Only the SHA-256 of
 * the whole secret is stored; the secret is high-entropy, so a slow hash adds nothing.
 * The prefix makes a leaked key findable by secret scanners.
 */
public final class ApiKeySecrets {

    public static final String KEY_PREFIX = "mfk_live_";
    private static final int RANDOM_BYTES = 32;
    /** ceil(256 / log2(62)): every 32-byte value fits, shorter encodings are left-padded. */
    private static final int BODY_LENGTH = 43;
    private static final int DISPLAY_PREFIX_LENGTH = 12;
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final BigInteger SIXTY_TWO = BigInteger.valueOf(62);
    private static final Pattern SHAPE = Pattern.compile("^mfk_live_[0-9A-Za-z]{31,51}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeySecrets() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        BigInteger n = new BigInteger(1, bytes);
        char[] body = new char[BODY_LENGTH];
        for (int i = BODY_LENGTH - 1; i >= 0; i--) {
            BigInteger[] qr = n.divideAndRemainder(SIXTY_TWO);
            body[i] = BASE62[qr[1].intValue()];
            n = qr[0];
        }
        return KEY_PREFIX + new String(body);
    }

    /** SHA-256 of the full secret, lowercase hex (64 characters). */
    public static String hash(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The first 12 characters, shown in lists so a key can be told apart without its secret. */
    public static String prefix(String secret) {
        return secret.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /** {@code mfk_live_} + a base62 tail, 40 to 60 characters in all. */
    public static boolean looksLikeKey(String s) {
        return s != null && SHAPE.matcher(s).matches();
    }
}
