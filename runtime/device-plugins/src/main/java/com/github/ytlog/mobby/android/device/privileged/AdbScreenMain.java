package com.github.ytlog.mobby.android.device.privileged;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Only a paired local ADB connection starts this entry point, never a CLI or Node process. */
public final class AdbScreenMain {
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("shell_required");
        File apk = new File(System.getenv("CLASSPATH"));
        if (!apk.isAbsolute() || !apk.isFile()) throw new IOException("worker_apk_missing");
        String name = "mobby-screen-" + UUID.nameUUIDFromBytes(apk.getPath().getBytes(StandardCharsets.UTF_8));
        if (args.length == 1 && "--worker".equals(args[0])) {
            // ProcessBuilder's child is not its parent's process-group leader. Leave the ADB
            // shell session before returning the private endpoint, so closing ADB cannot kill it.
            Os.setsid();
            try (LocalServerSocket rendezvous = new LocalServerSocket(name); ScreenServer server = new ScreenServer(apk)) {
                String endpoint = server.endpoint();
                Thread locator = new Thread(() -> publishEndpoint(rendezvous, endpoint), "screen-worker-locator");
                locator.setDaemon(true);
                locator.start();
                System.out.println(endpoint); System.out.flush();
                try (FileInputStream input = new FileInputStream("/dev/null");
                     FileOutputStream output = new FileOutputStream("/dev/null")) {
                    Os.dup2(input.getFD(), 0); Os.dup2(output.getFD(), 1); Os.dup2(output.getFD(), 2);
                }
                server.loop();
            }
        } else if (args.length == 0) {
            String endpoint = runningEndpoint(name);
            if (endpoint == null) {
                ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", AdbScreenMain.class.getName(), "--worker");
                builder.redirectError(new File("/dev/null"));
                try (InputStream input = builder.start().getInputStream()) {
                    endpoint = readEndpoint(input);
                }
                // Another starter may have won the socket bind while this process was launching.
                if (endpoint == null) endpoint = runningEndpoint(name);
            }
            if (endpoint == null) throw new IOException("worker_start_failed");
            System.out.println(endpoint); System.out.flush();
        } else throw new IllegalArgumentException("invalid_arguments");
        System.exit(0);
    }

    /** Only another shell process can obtain the in-memory endpoint through the local rendezvous. */
    private static void publishEndpoint(LocalServerSocket listener, String endpoint) {
        byte[] response = (endpoint + "\n").getBytes(StandardCharsets.UTF_8);
        while (true) {
            LocalSocket socket;
            try { socket = listener.accept(); }
            catch (IOException closed) { return; }
            try (LocalSocket client = socket) {
                if (client.getPeerCredentials().getUid() == 2000) client.getOutputStream().write(response);
            } catch (IOException disconnected) { /* The next lookup can still use this worker. */ }
        }
    }

    private static String runningEndpoint(String name) throws IOException {
        try (LocalSocket socket = new LocalSocket()) {
            try { socket.connect(new LocalSocketAddress(name)); }
            catch (IOException absent) { return null; }
            if (socket.getPeerCredentials().getUid() != 2000) throw new SecurityException("worker_locator_identity");
            socket.setSoTimeout(5_000);
            return readEndpoint(socket.getInputStream());
        }
    }

    private static String readEndpoint(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return line.toString("UTF-8");
            if (line.size() == 1024) throw new IOException("worker_endpoint_size");
            line.write(value);
        }
        return null;
    }
}
