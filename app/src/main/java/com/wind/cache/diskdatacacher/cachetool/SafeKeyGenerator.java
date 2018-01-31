package com.wind.cache.diskdatacacher.cachetool;

import android.util.LruCache;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 将key转换为SHA-256编码之后的key，以便于设为文件名
 */

class SafeKeyGenerator {

    String STRING_CHARSET_NAME = "UTF-8";
    Charset CHARSET = Charset.forName(STRING_CHARSET_NAME);

    private static final char[] HEX_CHAR_ARRAY = "0123456789abcdef".toCharArray();

    private final LruCache<String, String> loadIdToSafeHash = new LruCache(1000);

    SafeKeyGenerator() {
    }

    public String getSafeKey(String key) {
        String safeKey;
        safeKey = loadIdToSafeHash.get(key);
        if (safeKey == null) {
            safeKey = calculateHexStringDigest(key);
            loadIdToSafeHash.put(key, safeKey);
        }
        return safeKey;
    }

    private String calculateHexStringDigest(String key) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(key.getBytes(CHARSET));
            return bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Taken from:
    // http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java/9655275#9655275
    //copyed from Glide, com.bumptech.glide.load.engine.cache.SafeKeyGenerator
    @SuppressWarnings("PMD.UseVarargs")
    private static String bytesToHex(byte[] bytes) {
        int v;
        int length = bytes.length;
        char[] hexChars = new char[length * 2];
        for (int j = 0; j < length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_CHAR_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_CHAR_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
