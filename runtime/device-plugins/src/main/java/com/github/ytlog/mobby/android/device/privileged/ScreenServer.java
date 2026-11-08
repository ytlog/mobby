package com.github.ytlog.mobby.android.device.privileged;

import android.os.Process;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Private, authenticated transport. No arbitrary command execution is exposed to clients. */
public final class ScreenServer implements AutoCloseable {
    private final ServerSocket server;
    private final String token;
    private final Thread thread;
    private final File ownerApk;
    private volatile Socket client;
    private volatile boolean closed;
    private VirtualScreen screen;

    public ScreenServer() throws IOException { this(null); }

    public ScreenServer(File ownerApk) throws IOException {
        if (Process.myUid() != 0 && Process.myUid() != 2000) throw new SecurityException("privileged_uid_required");
        this.ownerApk = ownerApk;
        // UiAutomation on API 37 also binds a handler to the process main looper.
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
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

    public void loop() {
        new Thread(() -> {
            try { thread.join(); System.exit(0); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); System.exit(1); }
        }, "screen-worker-exit").start();
        android.os.Looper.loop();
    }

    private void serve() {
        long deadline = System.nanoTime() + 15_000_000_000L;
        boolean hasAuthenticatedClient = false;
        try {
            while (!closed && (ownerApk == null || ownerApk.isFile())) {
                Socket accepted;
                try {
                    server.setSoTimeout(hasAuthenticatedClient ? 30_000 : remainingMillis(deadline));
                    accepted = server.accept();
                } catch (SocketTimeoutException timeout) {
                    if (hasAuthenticatedClient) continue;
                    break;
                }
                try (Socket socket = accepted) {
                    client = socket;
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    long authDeadline = System.nanoTime() + 5_000_000_000L;
                    if (!authenticate(socket, input, hasAuthenticatedClient ? authDeadline : Math.min(deadline, authDeadline))) continue;
                    hasAuthenticatedClient = true;
                    socket.setSoTimeout(120_000);
                    write(output, new JSONObject().put("ok", true).put("uid", Process.myUid()));
                    serveClient(input, output);
                } catch (IOException | org.json.JSONException disconnected) {
                    // Socket failures end the session. The shell worker can serve the next app process.
                } finally {
                    client = null;
                    releaseScreen();
                }
                if (ownerApk == null && hasAuthenticatedClient) break;
            }
        } catch (IOException ignored) {
            // A failed listener is observed by the client as a disconnect.
        } finally { close(); }
    }

    private void serveClient(DataInputStream input, DataOutputStream output) throws IOException, org.json.JSONException {
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
                    catch (Throwable failure) {
                        try { created.close(); }
                        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
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
    }

    private boolean authenticate(Socket socket, DataInputStream input, long deadline) {
        try {
            byte[] header = new byte[4];
            readBefore(socket, input, header, deadline);
            int size = ByteBuffer.wrap(header).getInt();
            if (size <= 0 || size > 1024) return false;
            byte[] bytes = new byte[size];
            readBefore(socket, input, bytes, deadline);
            String supplied = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("token");
            return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException rejected) {
            return false;
        }
    }

    private static void readBefore(Socket socket, DataInputStream input, byte[] bytes, long deadline) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            socket.setSoTimeout(remainingMillis(deadline));
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException();
        return (int) Math.max(1, remaining / 1_000_000L);
    }

    private static JSONObject read(DataInputStream input) throws IOException, org.json.JSONException {
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
