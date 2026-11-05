package com.github.ytlog.mobby.android.device.privileged;

/** Launched only after the user selects root and grants the su request. */
public final class RootScreenMain {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("root_required");
        try (ScreenServer server = new ScreenServer()) {
            System.out.println(server.endpoint()); System.out.flush(); server.loop();
        }
        System.exit(0);
    }
}
