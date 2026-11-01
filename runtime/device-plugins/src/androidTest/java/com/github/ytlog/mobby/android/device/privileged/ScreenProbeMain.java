package com.github.ytlog.mobby.android.device.privileged;

/** ADB-shell probe of the exact worker used by Shizuku; not included in the application APK. */
public final class ScreenProbeMain {
    public static void main(String[] args) throws Exception {
        try (ScreenServer server = new ScreenServer()) {
            System.out.println(server.endpoint()); System.out.flush(); server.await();
        }
        System.exit(0);
    }
}
