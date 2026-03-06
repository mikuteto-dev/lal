package jp.mikumiku.lal.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class LALAccessChecker {

    private static final byte[] BASE_TOKEN;
    private static final ThreadLocal<byte[]> ACTIVE_TOKEN = new ThreadLocal<>();

    private static final StackWalker WALKER;

    private static final String LAL_PACKAGE = "jp.mikumiku.lal.";
    private static final String LAL_METHOD_PREFIX = "lal$";

    private static final String[] SKIP_PACKAGES = {
            "java.", "sun.", "jdk.", "com.sun.", "javax."
    };

    private static final ConcurrentHashMap<String, Boolean> mixinMergedCache = new ConcurrentHashMap<>();
    private static volatile long lastCacheClear = System.currentTimeMillis();
    private static final long CACHE_TTL_MS = 10000;

    static {
        byte[] token = new byte[128];
        try {
            new SecureRandom().nextBytes(token);
        } catch (Throwable t) {
            for (int i = 0; i < token.length; i++) {
                token[i] = (byte) (System.nanoTime() ^ (i * 31));
            }
        }
        BASE_TOKEN = token;

        StackWalker w;
        try {
            w = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        } catch (Throwable t) {
            w = null;
        }
        WALKER = w;
    }

    public static boolean isCallerFromLAL() {
        if (WALKER != null) {
            try {
                return WALKER.walk(frames -> {
                    var it = frames.skip(2).limit(15).iterator();
                    while (it.hasNext()) {
                        var frame = it.next();
                        String className = frame.getClassName();
                        if (className.startsWith(LAL_PACKAGE)) return true;
                        if (frame.getMethodName().startsWith(LAL_METHOD_PREFIX)) return true;
                        boolean isInternal = false;
                        for (String pkg : SKIP_PACKAGES) {
                            if (className.startsWith(pkg)) {
                                isInternal = true;
                                break;
                            }
                        }
                        if (isInternal) continue;
                        if (checkMixinMergedFromLAL(frame)) return true;
                        return false;
                    }
                    return false;
                });
            } catch (Throwable t) {
                return fallbackIsCallerFromLAL();
            }
        }
        return fallbackIsCallerFromLAL();
    }

    private static boolean fallbackIsCallerFromLAL() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 15); i++) {
                String className = stack[i].getClassName();
                if (className.startsWith(LAL_PACKAGE)) return true;
                if (stack[i].getMethodName().startsWith(LAL_METHOD_PREFIX)) return true;
                boolean isInternal = false;
                for (String pkg : SKIP_PACKAGES) {
                    if (className.startsWith(pkg)) {
                        isInternal = true;
                        break;
                    }
                }
                if (isInternal) continue;
                return false;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean checkMixinMergedFromLAL(StackWalker.StackFrame frame) {
        long now = System.currentTimeMillis();
        if (now - lastCacheClear > CACHE_TTL_MS) {
            mixinMergedCache.clear();
            lastCacheClear = now;
        }

        try {
            Class<?> clazz = frame.getDeclaringClass();
            String methodName = frame.getMethodName();
            String cacheKey = clazz.getName() + "#" + methodName;

            Boolean cached = mixinMergedCache.get(cacheKey);
            if (cached != null) return cached;

            boolean result = false;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                for (Annotation ann : m.getDeclaredAnnotations()) {
                    String annName = ann.annotationType().getName();
                    if (annName.contains("MixinMerged")) {
                        try {
                            Method mixinMethod = ann.annotationType().getMethod("mixin");
                            String mixinClass = (String) mixinMethod.invoke(ann);
                            if (mixinClass != null && mixinClass.startsWith(LAL_PACKAGE)) {
                                result = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (result) break;
            }

            mixinMergedCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void performPrivilegedAction(Runnable action) {
        if (!isCallerFromLAL()) return;
        ACTIVE_TOKEN.set(BASE_TOKEN);
        try {
            action.run();
        } finally {
            ACTIVE_TOKEN.remove();
        }
    }

    public static boolean checkAccess() {
        try {
            if (!isCallerFromLAL()) return false;
            byte[] active = ACTIVE_TOKEN.get();
            if (active == null || BASE_TOKEN == null) return false;
            int result = 0;
            for (int i = 0; i < Math.min(active.length, BASE_TOKEN.length); i++) {
                result |= active[i] ^ BASE_TOKEN[i];
            }
            return result == 0 && active.length == BASE_TOKEN.length;
        } catch (Throwable t) {
            return false;
        }
    }

    public static byte[] getBaseToken() {
        if (!isCallerFromLAL()) return null;
        return BASE_TOKEN;
    }

    public static byte[] computeHmacCanary(byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(BASE_TOKEN, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    public static boolean verifyHmacCanary(byte[] data, byte[] expectedHmac) {
        try {
            byte[] computed = computeHmacCanary(data);
            if (computed.length != expectedHmac.length) return false;
            int result = 0;
            for (int i = 0; i < computed.length; i++) {
                result |= computed[i] ^ expectedHmac[i];
            }
            return result == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
