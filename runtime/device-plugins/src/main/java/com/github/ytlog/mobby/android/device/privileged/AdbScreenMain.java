package com.github.ytlog.mobby.android.device.privileged;

import android.system.Os;
import java.io.*;

/** Only a paired local ADB connection starts this entry point, never a CLI or Node process. */
public final class AdbScreenMain {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("shell_required");
        if (args.length == 1 && "--worker".equals(args[0])) {
            // ProcessBuilder's child is not its parent's process-group leader. Leave the ADB
            // shell session before returning the private endpoint, so closing ADB cannot kill it.
            Os.setsid();
            try (ScreenServer server = new ScreenServer()) {
                System.out.println(server.endpoint()); System.out.flush();
                try (FileInputStream input = new FileInputStream("/dev/null");
                     FileOutputStream output = new FileOutputStream("/dev/null")) {
                    Os.dup2(input.getFD(), 0); Os.dup2(output.getFD(), 1); Os.dup2(output.getFD(), 2);
                }
                server.await();
            }
        } else if (args.length == 0) {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", AdbScreenMain.class.getName(), "--worker");
            builder.redirectError(new File("/dev/null"));
            Process worker = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(worker.getInputStream()))) {
                String endpoint = reader.readLine();
                if (endpoint == null || endpoint.length() > 1024) throw new IOException("worker_start_failed");
                System.out.println(endpoint); System.out.flush();
            }
        } else throw new IllegalArgumentException("invalid_arguments");
        System.exit(0);
    }
}
