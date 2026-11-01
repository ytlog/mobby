package com.github.ytlog.mobby.android.device.privileged;

import android.os.Process;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Private, authenticated transport. No arbitrary command execution is exposed to clients. */
public final class ScreenServer implements AutoCloseable {
    private final ServerSocket server;
    private final String token;
    private final Thread thread;
    private volatile Socket client;
    private volatile boolean closed;
    private VirtualScreen screen;

    public ScreenServer() throws IOException {
        if (Process.myUid() != 0 && Process.myUid() != 2000) throw new SecurityException("privileged_uid_required");
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(15_000);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP);
        thread = new Thread(this::serve, "virtual-screen-server");
        thread.start();
    }

    public String endpoint() {
        try { return new JSONObject().put("port", server.getLocalPort()).put("token", token).put("uid", Process.myUid()).toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void await() throws InterruptedException { thread.join(); }

    private void serve() {
        long deadline = System.nanoTime() + 15_000_000_000L;
        try {
            while (!closed && System.nanoTime() < deadline) {
                try (Socket socket = server.accept()) {
                    client = socket;
                    socket.setSoTimeout(5_000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    JSONObject hello = read(input);
                    if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), hello.optString("token").getBytes(StandardCharsets.UTF_8))) continue;
                    socket.setSoTimeout(120_000);
                    write(output, new JSONObject().put("ok", true).put("uid", Process.myUid()));
                    while (!closed) {
                        JSONObject request = read(input);
                        String action = request.getString("action");
                        JSONObject result;
                        try {
                            if ("ping".equals(action)) result = new JSONObject();
                            else if ("open".equals(action)) {
                                if (screen != null) throw new IllegalStateException("virtual_screen_busy");
                                VirtualScreen created = new VirtualScreen();
                                try { result = created.open(); screen = created; }
                                catch (Throwable failure) { created.close(); throw failure; }
                            } else if ("close".equals(action)) {
                                releaseScreen(); result = new JSONObject();
                            } else {
                                if (screen == null) throw new IllegalStateException("virtual_screen_not_open");
                                result = screen.execute(action, request.optJSONObject("args"));
                            }
                            result.put("ok", true);
                        } catch (Throwable failure) {
                            // Errors identify the operation, never its arguments, text, screenshot or credentials.
                            Throwable cause = failure;
                            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                            result = new JSONObject().put("ok", false).put("error", "virtual_screen_" + action + "_failed")
                                .put("cause", cause.getClass().getSimpleName());
                            String detail = cause.getMessage();
                            if (detail != null && detail.matches("[a-z][a-z0-9_]{0,79}")) result.put("detail", detail);
                        }
                        write(output, result);
                    }
                    break;
                } catch (SocketTimeoutException timeout) { break; }
                catch (EOFException disconnected) { break; }
            }
        } catch (Exception ignored) {
            // A broken transport is observed by the client as failure, never as a successful action.
        } finally { close(); }
    }

    private static JSONObject read(DataInputStream input) throws Exception {
        int size = input.readInt();
        if (size <= 0 || size > 65_536) throw new IOException("request_size");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    private static void write(DataOutputStream output, JSONObject value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("response_size");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private synchronized void releaseScreen() {
        if (screen != null) { screen.close(); screen = null; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        if (client != null) try { client.close(); } catch (IOException ignored) { }
        releaseScreen();
    }
}
