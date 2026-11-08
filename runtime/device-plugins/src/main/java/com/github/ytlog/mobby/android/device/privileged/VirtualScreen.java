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
    private UiAutomation automation;
    private final List<AccessibilityNodeInfo> privateInputTargets = new ArrayList<>();
    private boolean privateInputFocusedOnly;
    private final List<String> privateValues = new ArrayList<>();
    private VirtualDisplay display;
    private ImageReader reader;
    private volatile byte[] jpeg;
    private volatile long frameAt;
    private final Object frameLock = new Object();
    private boolean manualControl;
    private volatile boolean closed;
    private int displayId = -1;
    private Context context;
    private Object taskManager;
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
            reader.setOnImageAvailableListener(this::onImage, new Handler(callbacks.getLooper()));
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
            // Text uses ACTION_SET_TEXT. Never let a virtual editor summon its keyboard on the main display.
            windowInterface.getMethod("setDisplayImePolicy", int.class, int.class).invoke(windowManager, displayId, 2);
            Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
            Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connection).newInstance(callbacks.getLooper(), binder);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1); // Do not suppress existing accessibility services.
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            return metadata(new JSONObject());
        });
    }

    JSONObject execute(String action, JSONObject arguments) throws Exception {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        return call(() -> {
            if (display == null || displayId <= 0 || !display.getDisplay().isValid()) throw new IllegalStateException("invalid_display");
            if ("frame".equals(action)) return screenshot(metadata(new JSONObject()));
            if ("manual_begin".equals(action)) {
                clearPrivateInputTarget();
                manualControl = true;
                return metadata(new JSONObject());
            }
            if ("manual_end".equals(action)) {
                if (!manualControl) throw new IllegalStateException("manual_control_inactive");
                clearPrivateInputTarget();
                synchronized (frameLock) {
                    try (Image discarded = reader.acquireLatestImage()) { }
                    jpeg = null;
                    frameAt = 0;
                }
                manualControl = false;
                return metadata(new JSONObject());
            }
            if ("manual_gesture".equals(action)) {
                if (!manualControl) throw new IllegalStateException("manual_control_inactive");
                manualGesture(new JSONArray(args.getString("points")));
                return metadata(new JSONObject());
            }
            if (manualControl && !Set.of("back", "prepare_private_input", "private_input", "cancel_private_input").contains(action))
                throw new IllegalStateException("manual_control_active");
            if ("prepare_private_input".equals(action)) {
                clearPrivateInputTarget();
                privateInputFocusedOnly = args.optBoolean("focusedOnly");
                privateInputTargets.addAll(privateInputFields(privateInputFocusedOnly));
                if (privateInputTargets.isEmpty() || privateInputTargets.size() + privateValues.size() > 20) {
                    clearPrivateInputTarget();
                    throw new IllegalStateException("private_input_field_count");
                }
                JSONArray labels = new JSONArray();
                for (AccessibilityNodeInfo field : privateInputTargets) labels.put(privateInputLabel(field));
                return metadata(new JSONObject().put("labels", labels));
            }
            if ("cancel_private_input".equals(action)) {
                clearPrivateInputTarget();
                return metadata(new JSONObject());
            }
            if ("private_input".equals(action)) {
                try {
                    JSONArray values = new JSONArray(args.getString("values"));
                    List<AccessibilityNodeInfo> fields = privateInputFields(privateInputFocusedOnly);
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
                AccessibilityNodeInfo node = root();
                try { return snapshot(node); } finally { node.recycle(); }
            }
            if ("launch".equals(action)) {
                String name = args.getString("package"); launch(name);
                return observe("Opened " + name + " on virtual display", false);
            }
            if ("tap".equals(action) || "swipe".equals(action)) {
                float x = fraction(args, "x") * (WIDTH - 1), y = fraction(args, "y") * (HEIGHT - 1);
                if ("tap".equals(action)) gesture(x, y, x, y, 80);
                else gesture(x, y, fraction(args, "toX") * (WIDTH - 1), fraction(args, "toY") * (HEIGHT - 1), 350);
                return observe("Input delivered to virtual display", true);
            }
            if ("back".equals(action)) {
                int key = KeyEvent.KEYCODE_BACK;
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0);
                KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, key, 0);
                inject(down); inject(up);
                return observe("Key delivered to virtual display", true);
            }
            AccessibilityNodeInfo root = root();
            try {
                if ("snapshot".equals(action)) return snapshot(root);
                if ("inspect".equals(action)) return evidence(root, args);
                if ("click".equals(action)) {
                    List<AccessibilityNodeInfo> matches = matches(root, args.getString("query"));
                    try {
                        if (matches.size() != 1) throw new IllegalStateException(matches.isEmpty() ? "control_not_found" : "control_not_unique");
                        if (!matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("click_refused");
                    } finally { for (AccessibilityNodeInfo node : matches) node.recycle(); }
                } else if ("type".equals(action)) {
                    String text = args.getString("text");
                    if (text.length() > 2000 || text.isEmpty()) throw new IllegalArgumentException("invalid_text");
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    try {
                        if (focused != null && !focused.isEditable()) { focused.recycle(); focused = null; }
                        if (focused == null) focused = soleEditable(root);
                        if (focused == null || focused.isPassword()) throw new IllegalStateException("no_editable_field");
                        Bundle bundle = new Bundle(); bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) throw new IllegalStateException("text_refused");
                    } finally { if (focused != null) focused.recycle(); }
                } else throw new IllegalArgumentException("unsupported_action");
            } finally { root.recycle(); }
            return observe("Action delivered to virtual display", true);
        });
    }

    private List<AccessibilityNodeInfo> privateInputFields(boolean focusedOnly) {
        AccessibilityNodeInfo root = root();
        try {
            if (focusedOnly) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null && editablePrivateField(focused)) return List.of(focused);
                if (focused != null) focused.recycle();
                return List.of();
            }
            List<AccessibilityNodeInfo> fields = new ArrayList<>();
            walk(root, 0, new int[]{0}, (node, depth) -> {
                if (editablePrivateField(node)) fields.add(AccessibilityNodeInfo.obtain(node));
            });
            return fields;
        } finally { root.recycle(); }
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

    private AccessibilityNodeInfo root() {
        List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(displayId);
        if (windows == null) throw new IllegalStateException("no_virtual_window");
        try {
            windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo node = window.getRoot();
                if (node != null) {
                    if (node.isVisibleToUser() && "com.github.ytlog.mobby.android".contentEquals(node.getPackageName())) return node;
                    node.recycle();
                }
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo node = window.getRoot();
                    if (node != null) return node;
                }
            }
            throw new IllegalStateException("no_virtual_app_window");
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private JSONObject observe(String text, boolean wait) throws Exception {
        if (wait) SystemClock.sleep(200);
        JSONObject result = metadata(new JSONObject().put("text", text));
        // A delivered input is still reported honestly if the app closes its last readable window.
        try { AccessibilityNodeInfo node = root(); try { result.put("packageName", String.valueOf(node.getPackageName())); } finally { node.recycle(); } }
        catch (IllegalStateException noWindow) { result.put("packageName", ""); }
        return agentScreenshot(result);
    }
    private JSONObject snapshot(AccessibilityNodeInfo root) throws Exception {
        StringBuilder text = new StringBuilder();
        walk(root, 0, new int[]{0}, (node, depth) -> {
            String label = node.isPassword() ? "[secure]" : redactPrivateValues(
                node.getText() != null ? node.getText().toString() : String.valueOf(node.getContentDescription()));
            text.append("  ".repeat(depth)).append(node.getClassName()).append(' ').append(label.substring(0, Math.min(label.length(), 80)))
                .append(node.isClickable() ? " clickable" : "").append('\n');
        });
        return agentScreenshot(metadata(new JSONObject().put("text", text).put("packageName", String.valueOf(root.getPackageName()))));
    }
    private JSONObject evidence(AccessibilityNodeInfo root, JSONObject args) throws Exception {
        StringBuilder shape = new StringBuilder();
        MessageDigest scene = MessageDigest.getInstance("SHA-256");
        final int[] editable = {0};
        walk(root, 0, new int[]{0}, (node, depth) -> {
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
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(shape.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format("%02x", b));
        StringBuilder sceneHash = new StringBuilder(); for (byte b : scene.digest()) sceneHash.append(String.format("%02x", b));
        List<AccessibilityNodeInfo> found = args.has("query") ? matches(root, args.getString("query")) : new ArrayList<>();
        int count = found.size(); for (AccessibilityNodeInfo node : found) node.recycle();
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        boolean focusedEditable = focused != null && focused.isEditable();
        if (!focusedEditable) { if (focused != null) focused.recycle(); focused = soleEditable(root); }
        try {
            boolean input = focused != null && focused.isEditable();
            boolean password = input && (focused.isPassword() || !redactPrivateValues(String.valueOf(focused.getText())).equals(String.valueOf(focused.getText())));
            return metadata(new JSONObject().put("packageName", String.valueOf(root.getPackageName())).put("shape", hash.toString())
                .put("scene", sceneHash.toString())
                .put("matches", count).put("editable", editable[0]).put("focusedEditable", focusedEditable).put("passwordInput", password)
                .put("inputMatches", input && !password && args.has("expectedInput") && args.getString("expectedInput").contentEquals(focused.getText() == null ? "" : focused.getText())));
        } finally { if (focused != null) focused.recycle(); }
    }
    private AccessibilityNodeInfo soleEditable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> inputs = new ArrayList<>();
        walk(root, 0, new int[]{0}, (node, depth) -> {
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
    private void launch(String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || name.equals("com.github.ytlog.mobby.android"))
            throw new IllegalArgumentException("invalid_package");
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
                AccessibilityNodeInfo node = root();
                try { if (name.contentEquals(node.getPackageName())) return; } finally { node.recycle(); }
            } catch (IllegalStateException noWindow) { /* A launch transition may not have a window yet. */ }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
        throw new IllegalStateException("app_not_on_virtual_display");
    }
    private interface NodeVisitor { void visit(AccessibilityNodeInfo node, int depth); }
    private void walk(AccessibilityNodeInfo node, int depth, int[] count, NodeVisitor visitor) {
        if (count[0]++ >= 150 || depth > 12) return;
        visitor.visit(node, depth);
        for (int i = 0; i < node.getChildCount() && count[0] < 150; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { walk(child, depth + 1, count, visitor); } finally { child.recycle(); }
        }
    }
    private List<AccessibilityNodeInfo> matches(AccessibilityNodeInfo root, String query) {
        if (query.isEmpty() || query.length() > 200) throw new IllegalArgumentException("invalid_query");
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        walk(root, 0, new int[]{0}, (node, depth) -> {
            if (!node.isVisibleToUser() || node.isPassword()) return;
            String text = redactPrivateValues(String.valueOf(node.getText()) + " " + node.getContentDescription());
            if (!text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return;
            AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
            while (target != null && !target.isClickable()) {
                AccessibilityNodeInfo parent = target.getParent(); target.recycle(); target = parent;
            }
            if (target != null) { if (!result.contains(target)) result.add(target); else target.recycle(); }
        });
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

    private void manualGesture(JSONArray points) throws Exception {
        int count = points.length();
        if (count < 2 || count > 256) throw new IllegalArgumentException("invalid_manual_gesture");
        float[] xs = new float[count], ys = new float[count];
        long[] times = new long[count];
        for (int i = 0; i < count; i++) {
            JSONArray point = points.getJSONArray(i);
            double x = point.getDouble(0), y = point.getDouble(1);
            long time = point.getLong(2);
            if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1) || time > 5000 ||
                (i == 0 ? time != 0 : time < times[i - 1])) throw new IllegalArgumentException("invalid_manual_gesture");
            xs[i] = (float) x * (WIDTH - 1); ys[i] = (float) y * (HEIGHT - 1); times[i] = time;
        }
        long start = SystemClock.uptimeMillis();
        int last = 0;
        boolean down = false;
        try {
            motion(start, start, MotionEvent.ACTION_DOWN, xs[0], ys[0]); down = true;
            for (int i = 1; i < count; i++) {
                long delay = start + times[i] - SystemClock.uptimeMillis();
                if (delay > 0) Thread.sleep(delay);
                motion(start, SystemClock.uptimeMillis(), i == count - 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, xs[i], ys[i]);
                last = i;
            }
            down = false;
        } finally {
            if (down) motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, xs[last], ys[last]);
        }
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
        return !manualControl && privateValues.isEmpty() ? screenshot(result) : result.put("screenshotStatus", "private_input");
    }
    private JSONObject screenshot(JSONObject result) throws Exception {
        byte[] frame = jpeg;
        result.put("screenshotStatus", frame == null ? "no_frame" : "captured");
        if (frame != null) result.put("jpeg", Base64.encodeToString(frame, Base64.NO_WRAP)).put("frameAt", frameAt);
        return result;
    }
    private void onImage(ImageReader source) {
        synchronized (frameLock) { captureFrame(source); }
    }

    private void captureFrame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || closed) return;
            if (System.currentTimeMillis() - frameAt < 300) return;
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
                jpeg = null;
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
