package android.util;

/**
 * Test-only stand-in for {@code android.util.Log}.
 *
 * <p>The production build depends on the {@code android.jar} stub, whose {@code Log} methods throw
 * {@code RuntimeException("Stub!")} when invoked on a host JVM. This class lives in {@code src/test}
 * so that {@code target/test-classes} precedes the stub jar on the test classpath, letting host-JVM
 * unit tests exercise code that logs without crashing. It is never packaged into the APK.
 */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }
}
