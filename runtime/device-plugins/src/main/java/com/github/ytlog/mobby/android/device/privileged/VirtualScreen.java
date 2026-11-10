package com.github.ytlog.mobby.android.device.privileged;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.github.ytlog.mobby.android.device.WindowOutline;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Runs exclusively in the privileged process. Every observation and event targets its own display. */
@android.annotation.TargetApi(33)
final class VirtualScreen implements AutoCloseable {
    private static final int WIDTH = 720, HEIGHT = 1280, DPI = 240;
    private final HandlerThread operations = new HandlerThread("virtual-screen-operations");
    private final HandlerThread callbacks = new HandlerThread("virtual-screen-callbacks");
    private Handler handler;
    private Handler frameHandler;
    private UiAutomation automation;
    private final List<AccessibilityNodeInfo> privateInputTargets = new ArrayList<>();
    private boolean privateInputFocusedOnly;
    private final List<String> privateValues = new ArrayList<>();
    private VirtualDisplay display;
    private ImageReader reader;
    private byte[] jpeg;
    private long frameAt;
    private long lastCaptureUptime;
    private boolean capturePending;
    private final Object frameLock = new Object();
    private volatile boolean manualControl;
    private long touchDownAt = -1;
    private float touchStartX, touchStartY, touchX, touchY;
    private boolean touchIsTap;
    private volatile boolean closed;
    private int displayId = -1;
    private Context context;
    private Object taskManager;
    // WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY.
    private static final int IME_ON_MAIN_DISPLAY = 1;
    private Class<?> taskInterface;
    private Method getTasks;

    VirtualScreen() {
        operations.start(); callbacks.start(); handler = new Handler(operations.getLooper());
    }

    JSONObject open() throws Exception {
        return call(() -> {
            if (Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("android_13_required");
            taskInterface = Class.forName("android.app.IActivityTaskManager");
            getTasks = resolveGetTasks(taskInterface);
            taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            context = new ContextWrapper(system) {
                @Override public String getPackageName() { return "com.android.shell"; }
                @Override public String getOpPackageName() { return "com.android.shell"; }
                @Override public Context getApplicationContext() { return this; }
                @Override public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
                }
            };
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager manager = ctor.newInstance(context);
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3);
            frameHandler = new Handler(callbacks.getLooper());
            reader.setOnImageAvailableListener(this::onImage, frameHandler);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 6) | (1 << 8);
            // Match platform capabilities by Android version; unsupported flags are never silently retried.
            if (Build.VERSION.SDK_INT >= 33) flags |= (1 << 10) | (1 << 11); // trusted, own display group
            if (Build.VERSION.SDK_INT >= 34) flags |= (1 << 14); // own focus
            display = manager.createVirtualDisplay("mobby-agent", WIDTH, HEIGHT, DPI, reader.getSurface(), flags);
            if (display == null) throw new IllegalStateException("display_creation_refused");
            displayId = display.getDisplay().getDisplayId();
            if (displayId == Display.DEFAULT_DISPLAY) throw new IllegalStateException("main_display_refused");
            IBinder windowBinder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
            Class<?> windowInterface = Class.forName("android.view.IWindowManager");
            Object windowManager = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, windowBinder);
            // Every keyboard a virtual display field requests shows on the main screen and types into that field,
            // also while the user operates the display from the takeover page.
            windowInterface.getMethod("setDisplayImePolicy", int.class, int.class).invoke(windowManager, displayId, IME_ON_MAIN_DISPLAY);
            Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
            Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connection).newInstance(callbacks.getLooper(), binder);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1); // Do not suppress existing accessibility services.
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags = automationFlags(info.flags);
            automation.setServiceInfo(info);
            return metadata(new JSONObject());
        });
    }

    /**
     * UiAutomationConnection registers with FLAG_INCLUDE_NOT_IMPORTANT_VIEWS, so every layout wrapper becomes a node. The
     * current-screen accessibility service never sees those; dropping the flag gives both backends the same tree, as
     * {@code uiautomator dump --compressed} does.
     */
    static int automationFlags(int registered) {
        return (registered | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
            & ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
    }

    JSONObject execute(String action, JSONObject arguments) throws Exception {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        return call(() -> {
            if (display == null || displayId <= 0 || !display.getDisplay().isValid()) throw new IllegalStateException("invalid_display");
            if ("frame".equals(action)) return screenshot(metadata(new JSONObject()), args.optLong("after"));
            if ("manual_begin".equals(action)) {
                clearPrivateInputTarget();
                rememberFocusedManualTextTarget();
                manualControl = true;
                return metadata(new JSONObject());
            }
            if ("manual_end".equals(action)) {
                if (!manualControl) throw new IllegalStateException("manual_control_inactive");
                cancelManualTouch();
                clearPrivateInputTarget();
                synchronized (frameLock) {
                    try (Image discarded = reader.acquireLatestImage()) { }
                    jpeg = null;
                    frameAt = 0;
                }
                manualControl = false;
                return metadata(new JSONObject());
            }
            if ("manual_touch".equals(action)) {
                if (!manualControl) throw new IllegalStateException("manual_control_inactive");
                manualTouch(args.getString("phase"), fraction(args, "x") * (WIDTH - 1), fraction(args, "y") * (HEIGHT - 1));
                return metadata(new JSONObject());
            }
            if ("manual_cancel_touch".equals(action)) {
                cancelManualTouch();
                return metadata(new JSONObject());
            }
            if ("manual_prepare_text".equals(action)) {
                if (!manualControl) throw new IllegalStateException("manual_control_inactive");
                rememberFocusedManualTextTarget();
                AccessibilityNodeInfo field = validatedManualTextTarget();
                try { return metadata(new JSONObject().put("label", privateInputLabel(field))); }
                finally { field.recycle(); }
            }
            if (manualControl && !Set.of("back", "private_input").contains(action))
                throw new IllegalStateException("manual_control_active");
            if ("prepare_private_input".equals(action)) {
                clearPrivateInputTarget();
                privateInputFocusedOnly = args.optBoolean("focusedOnly");
                privateInputTargets.addAll(privateInputFields(privateInputFocusedOnly));
                // A handoff offers the focused field when there is one and otherwise only the takeover.
                if (privateInputTargets.isEmpty() && args.optBoolean("allowNone"))
                    return metadata(new JSONObject().put("labels", new JSONArray()).put("secret", new JSONArray()));
                if (privateInputTargets.isEmpty() || privateInputTargets.size() + privateValues.size() > 20) {
                    clearPrivateInputTarget();
                    throw new IllegalStateException("private_input_field_count");
                }
                JSONArray labels = new JSONArray();
                JSONArray secret = new JSONArray();
                for (AccessibilityNodeInfo field : privateInputTargets) {
                    labels.put(privateInputLabel(field));
                    secret.put(field.isPassword());
                }
                return metadata(new JSONObject().put("labels", labels).put("secret", secret));
            }
            if ("cancel_private_input".equals(action)) {
                clearPrivateInputTarget();
                return metadata(new JSONObject());
            }
            if ("private_input".equals(action)) {
                try {
                    JSONArray values = new JSONArray(args.getString("values"));
                    List<AccessibilityNodeInfo> fields = manualControl ? List.of(validatedManualTextTarget()) : privateInputFields(privateInputFocusedOnly);
                    try {
                        if (!fields.equals(privateInputTargets) || values.length() != fields.size())
                            throw new IllegalStateException("private_input_fields_changed");
                        for (int index = 0; index < fields.size(); index++) {
                            String text = values.getString(index);
                            if (text.length() > 2000 || text.indexOf(0) >= 0)
                                throw new IllegalArgumentException("invalid_text");
                            if (!privateInputLabel(fields.get(index)).equals(privateInputLabel(privateInputTargets.get(index))))
                                throw new IllegalStateException("private_input_fields_changed");
                        }
                        for (int index = 0; index < fields.size(); index++) {
                            String text = values.getString(index);
                            if (text.isEmpty()) continue;
                            AccessibilityNodeInfo field = fields.get(index);
                            if (!field.refresh() || !editablePrivateField(field) ||
                                !privateInputLabel(field).equals(privateInputLabel(privateInputTargets.get(index))))
                                throw new IllegalStateException("private_input_fields_changed");
                            privateValues.add(text);
                            Bundle bundle = new Bundle();
                            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
                                throw new IllegalStateException("text_refused");
                        }
                    } finally { for (AccessibilityNodeInfo field : fields) field.recycle(); }
                    return metadata(new JSONObject());
                } finally { clearPrivateInputTarget(); }
            }
            if ("apps".equals(action)) return metadata(new JSONObject().put("text", apps()));
            if ("tasks".equals(action)) return metadata(new JSONObject().put("tasks", virtualTasks()));
            if ("focus_task".equals(action)) {
                focusTask(args.getInt("id")); return observe("Restored virtual task", false);
            }
            if ("observe".equals(action)) return observe("Closed virtual navigation", true);
            if ("navigation_snapshot".equals(action)) {
                awaitPackage("com.github.ytlog.mobby.android");
                List<Layer> layers = layers();
                try { return snapshot(layers); } finally { release(layers); }
            }
            if ("launch".equals(action)) {
                String name = args.getString("package"); launch(name);
                return observe("Opened " + name + " on virtual display", false);
            }
            if ("force_stop".equals(action)) {
                String name = args.getString("package"); forceStop(name);
                return observe("Force-stopped " + name, true);
            }
            if ("tap".equals(action) || "swipe".equals(action)) {
                float x = fraction(args, "x") * (WIDTH - 1), y = fraction(args, "y") * (HEIGHT - 1);
                if ("tap".equals(action)) gesture(x, y, x, y, 80);
                else gesture(x, y, fraction(args, "toX") * (WIDTH - 1), fraction(args, "toY") * (HEIGHT - 1), 350);
                return observe("Input delivered to virtual display", true);
            }
            if ("back".equals(action)) {
                if (manualControl) clearPrivateInputTarget();
                int key = KeyEvent.KEYCODE_BACK;
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0);
                KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, key, 0);
                inject(down); inject(up);
                return observe("Key delivered to virtual display", true);
            }
            List<Layer> layers = layers();
            try {
                if ("snapshot".equals(action)) return snapshot(layers);
                if ("inspect".equals(action)) return evidence(layers, args);
                if ("click".equals(action)) {
                    List<AccessibilityNodeInfo> matches = matches(layers, args.getString("query"));
                    try {
                        if (matches.size() != 1) throw new IllegalStateException(matches.isEmpty() ? "control_not_found" : "control_not_unique");
                        if (!matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("click_refused");
                    } finally { for (AccessibilityNodeInfo node : matches) node.recycle(); }
                } else if ("type".equals(action)) {
                    String text = args.getString("text");
                    if (text.length() > 2000 || text.isEmpty()) throw new IllegalArgumentException("invalid_text");
                    AccessibilityNodeInfo focused = inputFocus(layers);
                    try {
                        if (focused != null && !focused.isEditable()) { focused.recycle(); focused = null; }
                        if (focused == null) focused = soleEditable(layers);
                        if (focused == null || focused.isPassword()) throw new IllegalStateException("no_editable_field");
                        Bundle bundle = new Bundle(); bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) throw new IllegalStateException("text_refused");
                    } finally { if (focused != null) focused.recycle(); }
                } else throw new IllegalArgumentException("unsupported_action");
            } finally { release(layers); }
            return observe("Action delivered to virtual display", true);
        });
    }

    /**
     * The focused field, or every editable field of the topmost application window that has one. A popup without fields,
     * such as the tooltip Douyin shows over its search box, does not hide the page's fields below it.
     */
    private List<AccessibilityNodeInfo> privateInputFields(boolean focusedOnly) {
        if (focusedOnly) {
            AccessibilityNodeInfo focused = focusedEditableField();
            return focused == null ? List.of() : List.of(focused);
        }
        List<AccessibilityWindowInfo> windows = virtualWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                List<AccessibilityNodeInfo> fields = new ArrayList<>();
                try {
                    walk(root, 0, (node, depth) -> {
                        if (editablePrivateField(node)) fields.add(AccessibilityNodeInfo.obtain(node));
                    });
                } finally { root.recycle(); }
                if (!fields.isEmpty()) return fields;
            }
            return List.of();
        } finally { recycle(windows); }
    }

    /** Windows on the virtual display, topmost first. A page's field can sit below a popup window of the same app. */
    private List<AccessibilityWindowInfo> virtualWindows() {
        List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(displayId);
        if (windows == null) return new ArrayList<>();
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
        return sorted;
    }

    private static void recycle(List<AccessibilityWindowInfo> windows) {
        for (AccessibilityWindowInfo window : windows) window.recycle();
    }

    /**
     * Typing goes to the topmost application window that holds an input focus; a popup that takes no focus is skipped.
     * Returns that focus when it is an editable field, otherwise null.
     */
    private AccessibilityNodeInfo focusedEditableField() {
        List<AccessibilityWindowInfo> windows = virtualWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused == null) continue;
                    if (editablePrivateField(focused)) return focused;
                    focused.recycle();
                    return null;
                } finally { root.recycle(); }
            }
            return null;
        } finally { recycle(windows); }
    }

    private void rememberFocusedManualTextTarget() {
        AccessibilityNodeInfo focused = focusedEditableField();
        if (focused == null) return;
        clearPrivateInputTarget();
        privateInputTargets.add(focused);
    }

    /** The finger lands on the topmost window under it. A field below a covering window, or under the keyboard, is not chosen. */
    private void rememberManualTextTargetAt(int x, int y) {
        List<AccessibilityWindowInfo> windows = virtualWindows();
        Rect bounds = new Rect();
        try {
            for (AccessibilityWindowInfo window : windows) {
                window.getBoundsInScreen(bounds);
                if (!bounds.contains(x, y)) continue;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) return;
                int[] deepest = {-1};
                try {
                    walk(root, 0, (node, depth) -> {
                        if (depth < deepest[0] || !editablePrivateField(node)) return;
                        node.getBoundsInScreen(bounds);
                        if (!bounds.contains(x, y)) return;
                        if (depth > deepest[0]) {
                            clearPrivateInputTarget();
                            deepest[0] = depth;
                        }
                        privateInputTargets.add(AccessibilityNodeInfo.obtain(node));
                    });
                    if (privateInputTargets.size() != 1) clearPrivateInputTarget();
                } finally { root.recycle(); }
                return;
            }
        } finally { recycle(windows); }
    }

    /** Whether the window that held [node] is still on the virtual display and still shows the same app. */
    private boolean windowStillShows(AccessibilityNodeInfo node) {
        List<AccessibilityWindowInfo> windows = virtualWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() != node.getWindowId()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) return false;
                try { return Objects.equals(root.getPackageName(), node.getPackageName()); }
                finally { root.recycle(); }
            }
            return false;
        } finally { recycle(windows); }
    }

    private AccessibilityNodeInfo validatedManualTextTarget() {
        if (privateInputTargets.size() != 1) throw new IllegalStateException("manual_input_target_missing");
        if (privateValues.size() >= 20) throw new IllegalStateException("private_input_field_count");
        AccessibilityNodeInfo selected = privateInputTargets.get(0);
        if (!windowStillShows(selected)) throw new IllegalStateException("private_input_fields_changed");
        AccessibilityNodeInfo field = AccessibilityNodeInfo.obtain(selected);
        boolean valid = false;
        try {
            valid = field.refresh() && editablePrivateField(field) &&
                Objects.equals(field.getClassName(), selected.getClassName()) &&
                Objects.equals(field.getViewIdResourceName(), selected.getViewIdResourceName()) &&
                privateInputLabel(field).equals(privateInputLabel(selected));
            if (!valid) throw new IllegalStateException("private_input_fields_changed");
            return field;
        } finally { if (!valid) field.recycle(); }
    }

    private static boolean editablePrivateField(AccessibilityNodeInfo node) {
        return node.isEditable() && node.isEnabled() && node.isVisibleToUser();
    }

    private static String privateInputLabel(AccessibilityNodeInfo node) {
        CharSequence hint = node.getHintText();
        if (hint == null) return "";
        return hint.subSequence(0, Math.min(hint.length(), 80)).toString();
    }

    private void clearPrivateInputTarget() {
        for (AccessibilityNodeInfo target : privateInputTargets) target.recycle();
        privateInputTargets.clear();
    }

    /** A window the agent reads and acts on, with its root node and its area on the display. */
    private static final class Layer {
        final AccessibilityNodeInfo root;
        final Rect bounds = new Rect();
        final boolean focused;
        Layer(AccessibilityNodeInfo root, AccessibilityWindowInfo window) {
            this.root = root;
            window.getBoundsInScreen(bounds);
            focused = window.isFocused();
        }
    }

    private static void release(List<Layer> layers) {
        for (Layer layer : layers) layer.root.recycle();
    }

    /** The window under the others in [layers]: the focused page that windows above it partly cover. */
    private static AccessibilityNodeInfo base(List<Layer> layers) {
        return layers.get(layers.size() - 1).root;
    }

    /**
     * The windows the agent reads and acts on, topmost first: mobby's navigation list while it is shown, otherwise the
     * application windows from the top down to the one with input focus. A window above the focused one, such as the tooltip
     * Douyin shows over its search box, takes no focus and covers part of the page without replacing it, so both are read.
     * Windows below the focused one are behind it. When no window on the display has focus, every application window is read.
     */
    private List<Layer> layers() {
        List<AccessibilityWindowInfo> windows = virtualWindows();
        if (windows.isEmpty()) throw new IllegalStateException("no_virtual_window");
        List<Layer> layers = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo node = window.getRoot();
                if (node == null) continue;
                if (node.isVisibleToUser() && "com.github.ytlog.mobby.android".contentEquals(node.getPackageName())) {
                    layers.add(new Layer(node, window));
                    return layers;
                }
                node.recycle();
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo node = window.getRoot();
                if (node == null) continue;
                Layer layer = new Layer(node, window);
                layers.add(layer);
                if (layer.focused) break;
            }
            if (layers.isEmpty()) throw new IllegalStateException("no_virtual_app_window");
            return layers;
        } finally { recycle(windows); }
    }

    /** Typing goes to the input focus of the focused window; a covering window that takes no focus has none. */
    private static AccessibilityNodeInfo inputFocus(List<Layer> layers) {
        for (Layer layer : layers) {
            AccessibilityNodeInfo focused = layer.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) return focused;
        }
        return null;
    }

    private JSONObject observe(String text, boolean wait) throws Exception {
        if (wait) SystemClock.sleep(200);
        JSONObject result = metadata(new JSONObject().put("text", text));
        // A delivered input is still reported honestly if the app closes its last readable window.
        try { List<Layer> layers = layers(); try { result.put("packageName", String.valueOf(base(layers).getPackageName())); } finally { release(layers); } }
        catch (IllegalStateException noWindow) { result.put("packageName", ""); }
        return agentScreenshot(result);
    }
    private JSONObject snapshot(List<Layer> layers) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            if (layers.size() > 1) {
                text.append("-- window ").append(index + 1).append(" of ").append(layers.size()).append(": ")
                    .append(layer.root.getPackageName()).append(index == 0 ? ", on top" : "").append(layer.focused ? ", focused" : "")
                    .append(index > 0 ? ", parts under the windows above are covered" : "").append(" --\n");
            }
            text.append(WindowOutline.of(layer.root, this::redactPrivateValues));
        }
        return agentScreenshot(metadata(new JSONObject().put("text", text).put("packageName", String.valueOf(base(layers).getPackageName()))));
    }
    private JSONObject evidence(List<Layer> layers, JSONObject args) throws Exception {
        StringBuilder shape = new StringBuilder();
        MessageDigest scene = MessageDigest.getInstance("SHA-256");
        final int[] editable = {0};
        for (Layer layer : layers) {
            shape.append('|');
            scene.update((byte) 1);
            walk(layer.root, 0, (node, depth) -> {
                shape.append(node.getClassName()).append(':').append(node.isClickable()).append(':').append(node.isEditable()).append(':').append(node.getChildCount()).append(';');
                if (node.isEditable()) editable[0]++;
                if (node.isVisibleToUser()) {
                    String label = node.isPassword() || node.isEditable() ? "" : redactPrivateValues(
                        String.valueOf(node.getText() != null ? node.getText() : node.getContentDescription()));
                    String fingerprint = depth + ":" + node.getViewIdResourceName() + ":" + node.isChecked() + ":" +
                        label.substring(0, Math.min(label.length(), 256));
                    scene.update(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    scene.update((byte) 0);
                }
            });
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(shape.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format("%02x", b));
        StringBuilder sceneHash = new StringBuilder(); for (byte b : scene.digest()) sceneHash.append(String.format("%02x", b));
        List<AccessibilityNodeInfo> found = args.has("query") ? matches(layers, args.getString("query")) : new ArrayList<>();
        int count = found.size(); for (AccessibilityNodeInfo node : found) node.recycle();
        AccessibilityNodeInfo focused = inputFocus(layers);
        boolean focusedEditable = focused != null && focused.isEditable();
        if (!focusedEditable) { if (focused != null) focused.recycle(); focused = soleEditable(layers); }
        try {
            boolean input = focused != null && focused.isEditable();
            boolean password = input && (focused.isPassword() || !redactPrivateValues(String.valueOf(focused.getText())).equals(String.valueOf(focused.getText())));
            return metadata(new JSONObject().put("packageName", String.valueOf(base(layers).getPackageName())).put("shape", hash.toString())
                .put("scene", sceneHash.toString())
                .put("matches", count).put("editable", editable[0]).put("focusedEditable", focusedEditable).put("passwordInput", password)
                .put("inputMatches", input && !password && args.has("expectedInput") && args.getString("expectedInput").contentEquals(focused.getText() == null ? "" : focused.getText())));
        } finally { if (focused != null) focused.recycle(); }
    }
    private AccessibilityNodeInfo soleEditable(List<Layer> layers) {
        List<AccessibilityNodeInfo> inputs = new ArrayList<>();
        for (Layer layer : layers) walk(layer.root, 0, (node, depth) -> {
            if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()) inputs.add(AccessibilityNodeInfo.obtain(node));
        });
        if (inputs.size() == 1) return inputs.get(0);
        for (AccessibilityNodeInfo node : inputs) node.recycle();
        return null;
    }

    static Method resolveGetTasks(Class<?> taskInterface) throws NoSuchMethodException {
        // AOSP 13 has three arguments and 14 adds a display filter. Probe the actual
        // framework signature because an OEM can backport either contract.
        try {
            return taskInterface.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        } catch (NoSuchMethodException absent) {
            return taskInterface.getMethod("getTasks", int.class, boolean.class, boolean.class);
        }
    }
    @SuppressWarnings("unchecked")
    private List<ActivityManager.RunningTaskInfo> tasks(int targetDisplay) throws Exception {
        if (getTasks.getParameterCount() == 4)
            return (List<ActivityManager.RunningTaskInfo>) getTasks.invoke(taskManager, 1000, false, false, targetDisplay);
        return (List<ActivityManager.RunningTaskInfo>) getTasks.invoke(taskManager, 1000, false, false);
    }
    private int taskDisplay(ActivityManager.RunningTaskInfo task) throws Exception {
        return task.getClass().getField("displayId").getInt(task);
    }
    private boolean belongsTo(ActivityManager.RunningTaskInfo task, String name) {
        return task.baseActivity != null && name.equals(task.baseActivity.getPackageName());
    }
    private JSONArray virtualTasks() throws Exception {
        JSONArray items = new JSONArray();
        for (ActivityManager.RunningTaskInfo task : tasks(displayId)) {
            if (taskDisplay(task) == displayId && task.baseActivity != null)
                items.put(new JSONObject().put("id", task.taskId).put("package", task.baseActivity.getPackageName()));
        }
        return items;
    }
    private void focusTask(int id) throws Exception {
        ActivityManager.RunningTaskInfo selected = null;
        for (ActivityManager.RunningTaskInfo task : tasks(displayId)) if (task.taskId == id && taskDisplay(task) == displayId) selected = task;
        if (selected == null) throw new IllegalStateException("virtual_task_not_found");
        String name = (selected.topActivity != null ? selected.topActivity : selected.baseActivity).getPackageName();
        // Only a task still on our display may be brought forward; no main-display task IDs are accepted.
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        taskInterface.getMethod("moveTaskToFront", Class.forName("android.app.IApplicationThread"), String.class, int.class, int.class, Bundle.class)
            .invoke(taskManager, null, "com.android.shell", id, 0, options);
        awaitPackage(name);
    }
    private static void requireAppPackage(String name) {
        if (!name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || name.equals("com.github.ytlog.mobby.android"))
            throw new IllegalArgumentException("invalid_package");
    }
    /**
     * Ends every process and activity of a launchable app, including its tasks on the main display, so the next launch
     * starts it fresh. Apps without a launcher entry, such as system UI and services, are refused.
     */
    private void forceStop(String name) throws Exception {
        requireAppPackage(name);
        if (!Arrays.asList(apps().split("\n")).contains(name)) throw new IllegalStateException("app_not_launchable");
        command("/system/bin/am", "force-stop", "--user", "current", name);
    }
    private void launch(String name) throws Exception {
        requireAppPackage(name);
        for (ActivityManager.RunningTaskInfo task : tasks(displayId)) {
            if (taskDisplay(task) == displayId && belongsTo(task, name)) { focusTask(task.taskId); return; }
        }
        String resolved = command("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "current", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", name);
        String component = Arrays.stream(resolved.split("\\R")).map(String::trim)
            .filter(line -> line.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+") && line.startsWith(name + "/"))
            .findFirst().orElseThrow(() -> new IllegalStateException("launcher_not_found"));
        ActivityInfo info = context.getPackageManager().getActivityInfo(ComponentName.unflattenFromString(component), 0);
        // Single-task/single-instance apps can steal their existing task from another display.
        if (info.launchMode != ActivityInfo.LAUNCH_MULTIPLE && info.launchMode != ActivityInfo.LAUNCH_SINGLE_TOP) {
            for (ActivityManager.RunningTaskInfo task : tasks(-1))
                if (taskDisplay(task) != displayId && belongsTo(task, name)) throw new UnsupportedOperationException("app_already_on_other_display");
        }
        command("/system/bin/am", "start", "-W", "--display", String.valueOf(displayId), "--user", "current",
            "-f", "0x18000000", "-n", component);
        awaitPackage(name);
    }
    private void awaitPackage(String name) throws Exception {
        long until = SystemClock.uptimeMillis() + 3000;
        do {
            try {
                List<Layer> layers = layers();
                try { if (name.contentEquals(base(layers).getPackageName())) return; } finally { release(layers); }
            } catch (IllegalStateException noWindow) { /* A launch transition may not have a window yet. */ }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
        throw new IllegalStateException("app_not_on_virtual_display");
    }
    private interface NodeVisitor { void visit(AccessibilityNodeInfo node, int depth); }
    /** Visits the whole tree with no node or depth limit; it is as deep as the app's own recursively drawn view tree. */
    private void walk(AccessibilityNodeInfo node, int depth, NodeVisitor visitor) {
        visitor.visit(node, depth);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { walk(child, depth + 1, visitor); } finally { child.recycle(); }
        }
    }
    /**
     * Clickable controls whose text or description contains [query], in every read window. A control whose centre lies
     * under a window above its own is covered, as it is for a finger, and is not a match.
     */
    private List<AccessibilityNodeInfo> matches(List<Layer> layers, String query) {
        if (query.isEmpty() || query.length() > 200) throw new IllegalArgumentException("invalid_query");
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        Rect bounds = new Rect();
        for (int index = 0; index < layers.size(); index++) {
            List<Layer> above = layers.subList(0, index);
            walk(layers.get(index).root, 0, (node, depth) -> {
                if (!node.isVisibleToUser() || node.isPassword()) return;
                String text = redactPrivateValues(String.valueOf(node.getText()) + " " + node.getContentDescription());
                if (!text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return;
                AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
                while (target != null && !target.isClickable()) {
                    AccessibilityNodeInfo parent = target.getParent(); target.recycle(); target = parent;
                }
                if (target == null) return;
                target.getBoundsInScreen(bounds);
                boolean covered = false;
                for (Layer layer : above) covered |= layer.bounds.contains(bounds.centerX(), bounds.centerY());
                if (covered || result.contains(target)) target.recycle(); else result.add(target);
            });
        }
        return result;
    }
    private static float fraction(JSONObject args, String name) throws Exception {
        float n = Float.parseFloat(args.getString(name));
        if (!Float.isFinite(n) || n < 0 || n > 1) throw new IllegalArgumentException("invalid_coordinate");
        return n;
    }
    private void inject(InputEvent event) throws Exception {
        InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, displayId);
        if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("input_refused");
    }
    private void gesture(float x, float y, float toX, float toY, int duration) throws Exception {
        long start = SystemClock.uptimeMillis();
        boolean down = false;
        try {
            motion(start, start, MotionEvent.ACTION_DOWN, x, y); down = true;
            for (int t = 16; t < duration; t += 16) {
                SystemClock.sleep(16);
                motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + (toX - x) * t / duration, y + (toY - y) * t / duration);
            }
            motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX, toY); down = false;
        } finally { if (down) motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, toX, toY); }
    }
    private void motion(long start, long now, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(start, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { inject(event); } finally { event.recycle(); }
    }

    private void manualTouch(String phase, float x, float y) throws Exception {
        try {
            if ("DOWN".equals(phase)) {
                if (touchDownAt >= 0) throw new IllegalStateException("manual_touch_active");
                clearPrivateInputTarget();
                // Record the editable under the finger before input can change the window's focus.
                rememberManualTextTargetAt((int) x, (int) y);
                touchStartX = touchX = x;
                touchStartY = touchY = y;
                touchIsTap = true;
                touchDownAt = SystemClock.uptimeMillis();
                motion(touchDownAt, touchDownAt, MotionEvent.ACTION_DOWN, x, y);
                return;
            }
            if (touchDownAt < 0) throw new IllegalStateException("manual_touch_inactive");
            boolean release = "UP".equals(phase);
            if (!release && !"MOVE".equals(phase)) throw new IllegalArgumentException("invalid_manual_touch_phase");
            long now = SystemClock.uptimeMillis();
            if (touchIsTap && (Math.abs(x - touchStartX) > 12 || Math.abs(y - touchStartY) > 12 || now - touchDownAt > 500)) {
                touchIsTap = false;
                clearPrivateInputTarget();
            }
            // UP can supersede a queued MOVE. Apply the exact endpoint before releasing a drag.
            if (!release || (!touchIsTap && (x != touchX || y != touchY))) {
                motion(touchDownAt, now, MotionEvent.ACTION_MOVE, x, y);
                touchX = x;
                touchY = y;
            }
            if (release) {
                motion(touchDownAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
                touchDownAt = -1;
            }
        } catch (Exception failure) {
            try { cancelManualTouch(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void cancelManualTouch() throws Exception {
        if (touchDownAt < 0) return;
        long down = touchDownAt;
        touchDownAt = -1;
        clearPrivateInputTarget();
        motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, touchX, touchY);
    }
    private String apps() throws Exception {
        String output = command("/system/bin/cmd", "package", "query-activities", "--brief", "--components", "--user", "current",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER");
        SortedSet<String> packages = new TreeSet<>();
        for (String line : output.split("\\R")) if (line.trim().matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) packages.add(line.trim().split("/")[0]);
        return String.join("\n", packages);
    }
    private static String command(String... args) throws Exception {
        java.lang.Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 262144) { process.destroy(); return; }
                    bytes.write(buffer, 0, n);
                }
            } catch (IOException ignored) { }
        });
        drain.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new TimeoutException("command_timeout");
            drain.join(1000);
            String result = bytes.toString("UTF-8");
            if (process.exitValue() != 0 || result.contains("Error:") || result.contains("Exception")) throw new IllegalStateException("command_refused");
            return result;
        } finally { process.destroy(); }
    }
    private JSONObject metadata(JSONObject result) throws Exception {
        return result.put("displayId", displayId).put("width", WIDTH).put("height", HEIGHT).put("observedAtEpochMillis", System.currentTimeMillis());
    }
    private String redactPrivateValues(String text) {
        for (String value : privateValues) text = text.replace(value, "[secure]");
        return text;
    }
    private JSONObject agentScreenshot(JSONObject result) throws Exception {
        // Pixel output can reveal a value even when the app does not expose a text node.
        return !manualControl && privateValues.isEmpty() ? screenshot(result, 0) : result.put("screenshotStatus", "private_input");
    }
    private JSONObject screenshot(JSONObject result, long after) throws Exception {
        byte[] frame;
        long capturedAt;
        synchronized (frameLock) {
            frame = jpeg;
            capturedAt = frameAt;
        }
        if (frame == null) return result.put("screenshotStatus", "no_frame");
        if (capturedAt == after) return result.put("screenshotStatus", "unchanged");
        return result.put("screenshotStatus", "captured").put("jpeg", Base64.encodeToString(frame, Base64.NO_WRAP))
            .put("frameAt", capturedAt);
    }
    private void onImage(ImageReader source) {
        synchronized (frameLock) {
            if (closed || capturePending) return;
            long delay = lastCaptureUptime + (manualControl ? 65 : 300) - SystemClock.uptimeMillis();
            if (delay <= 0) {
                captureFrame(source);
            } else {
                // Retain the newest frame through the throttle interval, including the final frame of a drag.
                capturePending = true;
                frameHandler.postDelayed(() -> {
                    synchronized (frameLock) { capturePending = false; }
                    onImage(source);
                }, delay);
            }
        }
    }

    private void captureFrame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || closed) return;
            lastCaptureUptime = SystemClock.uptimeMillis();
            Image.Plane plane = image.getPlanes()[0];
            int padded = plane.getRowStride() / plane.getPixelStride();
            Bitmap bitmap = Bitmap.createBitmap(padded, HEIGHT, Bitmap.Config.ARGB_8888);
            try {
                ByteBuffer buffer = plane.getBuffer(); bitmap.copyPixelsFromBuffer(buffer);
                Bitmap crop = Bitmap.createBitmap(bitmap, 0, 0, WIDTH, HEIGHT);
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!crop.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) return;
                    if (bytes.size() <= 2 * 1024 * 1024) { jpeg = bytes.toByteArray(); frameAt = System.currentTimeMillis(); }
                } finally { if (crop != bitmap) crop.recycle(); }
            } finally { bitmap.recycle(); }
        } catch (RuntimeException failed) { jpeg = null; }
    }
    private <T> T call(Callable<T> block) throws Exception {
        if (closed) throw new IllegalStateException("virtual_screen_closed");
        FutureTask<T> task = new FutureTask<>(block); handler.post(task);
        try { return task.get(15, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { task.cancel(true); close(); throw timeout; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        CountDownLatch released = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> cleanupFailure = new java.util.concurrent.atomic.AtomicReference<>();
        // Cleanup follows any in-flight operation on the same looper, including a late createDisplay.
        handler.post(() -> {
            try {
                try { cancelManualTouch(); }
                catch (Exception failure) { cleanupFailure.compareAndSet(null, failure); }
                clearPrivateInputTarget();
                privateValues.clear();
                try { if (display != null) display.release(); }
                catch (RuntimeException failure) { cleanupFailure.compareAndSet(null, failure); }
                finally { display = null; }
                try { if (reader != null) reader.close(); }
                catch (RuntimeException failure) { cleanupFailure.compareAndSet(null, failure); }
                finally { reader = null; }
                try { if (automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation); }
                catch (Exception failure) { cleanupFailure.compareAndSet(null, failure); }
                finally { automation = null; }
                synchronized (frameLock) { jpeg = null; }
            } finally { callbacks.quitSafely(); operations.quitSafely(); released.countDown(); }
        });
        try {
            if (!released.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("display_cleanup_pending");
            if (cleanupFailure.get() != null) throw new IllegalStateException("display_cleanup_failed", cleanupFailure.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("display_cleanup_interrupted", interrupted);
        }
    }
}
