package jp.mikumiku.lal.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.UUID;

public class NativeLoader {

    private static final String EXPECTED_SHA256 = "e6547bc82e79ed07911fbae888137686d61827598d5d9b95b1b4fde7528e5bcb";

    private static volatile boolean loaded = false;
    private static volatile boolean attempted = false;

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        if (attempted) return false;
        attempted = true;
        try {
            loaded = loadFromResource();
        } catch (Throwable ignored) {}
        if (!loaded) {
            try {
                loaded = loadViaLibraryPath();
            } catch (Throwable ignored) {}
        }
        return loaded;
    }

    private static boolean loadFromResource() {
        try {
            char[] pathChars = new char[]{
                    '/', 'n', 'a', 't', 'i', 'v', 'e', '/',
                    'l', 'a', 'l', '.', 'd', 'l', 'l'
            };
            String resourcePath = new String(pathChars);
            InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) return false;
            String suffix = "." + randomSuffix();
            File tmp = File.createTempFile(randomPrefix(), suffix);
            tmp.deleteOnExit();
            try (OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
            }
            is.close();
            if (!verifyIntegrity(tmp)) {
                tmp.delete();
                return false;
            }
            System.load(tmp.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean loadViaLibraryPath() {
        try {
            char[] name = new char[]{'l', 'a', 'l'};
            System.loadLibrary(new String(name));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String randomPrefix() {
        return "t" + UUID.randomUUID().toString().substring(0, 8) + "_";
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[]{0x64, 0x6C, 0x6C};
        return new String(bytes);
    }

    private static boolean verifyIntegrity(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) digest.update(buf, 0, len);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return EXPECTED_SHA256.equals(sb.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
