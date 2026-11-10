package com.github.ytlog.mobby.android.device;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The agent's text view of the screen and the node rules both backends share.
 *
 * <p>The whole tree is read, without a node count or depth limit. A node gets a line when it is on screen and has text, a
 * hint, a description, a state, an error, an image or an action (click, long click, edit, scroll, check). Nodes that are not
 * visible to the user are skipped with their subtrees, as {@code uiautomator dump} does; containers with none of the above are
 * walked through without a line. Each line is indented by its listed ancestors and ends with the node's area as fractions of
 * the display, {@code [left,top,right,bottom]}, the units tap and swipe take. Line breaks are written as {@code \n}. A text
 * longer than {@link #MAX_TEXT} characters is cut there and says how many characters were left out.
 */
public final class WindowOutline {
    /** Characters of one text or description; an article body or chat history in one node would otherwise flood the outline. */
    public static final int MAX_TEXT = 500;
    /** A long press in both backends: above the platform timeout of 400 to 500 ms, short of the 1.5 s some apps treat as longer. */
    public static final int LONG_PRESS_MILLIS = 800;

    private WindowOutline() { }

    /** The parts of an accessibility node the outline reads. A child returned by {@link #child} is released by the caller. */
    public interface Node {
        String className();
        CharSequence text();
        CharSequence hint();
        CharSequence description();
        CharSequence state();
        CharSequence error();
        boolean visible();
        boolean enabled();
        boolean clickable();
        boolean longClickable();
        boolean editable();
        boolean scrollable();
        boolean checkable();
        boolean checked();
        boolean selected();
        boolean focused();
        boolean password();
        /** Left, top, right and bottom in display pixels. */
        int[] bounds();
        int childCount();
        Node child(int index);
        void release();
    }

    /** Lists [root] on a display of [width] by [height] pixels. */
    public static String of(AccessibilityNodeInfo root, int width, int height, UnaryOperator<String> redact) {
        return of(new Accessibility(root, false), width, height, redact);
    }

    static String of(Node root, int width, int height, UnaryOperator<String> redact) {
        StringBuilder out = new StringBuilder();
        line(root, 0, Math.max(1, width), Math.max(1, height), out, redact);
        return out.toString();
    }

    /** The line put before each window's outline when the agent reads more than one window, topmost first. */
    public static String header(int index, int count, CharSequence packageName, boolean focused) {
        return "-- window " + (index + 1) + " of " + count + ": " + packageName + (index == 0 ? ", on top" : "") +
            (focused ? ", focused" : "") + (index > 0 ? ", parts under the windows above are covered" : "") + " --\n";
    }

    /** Recursion is as deep as the app's own view tree, which the app measures and draws recursively on a smaller stack. */
    private static void line(Node node, int indent, int width, int height, StringBuilder out, UnaryOperator<String> redact) {
        if (!node.visible()) return;
        // Redaction runs on the whole value first, so a cut never leaves part of a private value behind.
        String text = node.password() ? "[secure]" : shorten(redact.apply(clean(node.text())));
        String hint = shorten(redact.apply(clean(node.hint())));
        String description = shorten(redact.apply(clean(node.description())));
        String state = shorten(redact.apply(clean(node.state())));
        String error = shorten(redact.apply(clean(node.error())));
        String name = simpleName(node.className());
        boolean image = name.contains("Image");
        boolean action = node.clickable() || node.longClickable() || node.editable() || node.scrollable() || node.checkable();
        int childIndent = indent;
        if (!text.isEmpty() || !hint.isEmpty() || !description.isEmpty() || !state.isEmpty() || !error.isEmpty() || image || action) {
            for (int space = 0; space < indent; space++) out.append("  ");
            out.append(name);
            if (!text.isEmpty()) out.append(" \"").append(text).append('"');
            if (!hint.isEmpty() && !hint.equals(text)) out.append(" hint=\"").append(hint).append('"');
            if (!description.isEmpty() && !description.equals(text)) out.append(" desc=\"").append(description).append('"');
            if (!state.isEmpty()) out.append(" state=\"").append(state).append('"');
            if (!error.isEmpty()) out.append(" error=\"").append(error).append('"');
            if (node.clickable()) out.append(" clickable");
            if (node.longClickable()) out.append(" long-clickable");
            if (node.editable()) out.append(" editable");
            if (node.scrollable()) out.append(" scrollable");
            if (node.checkable()) out.append(node.checked() ? " checked" : " unchecked");
            if (node.selected()) out.append(" selected");
            if (node.focused()) out.append(" focused");
            if (!node.enabled()) out.append(" disabled");
            int[] box = node.bounds();
            out.append(String.format(Locale.ROOT, " [%.3f,%.3f,%.3f,%.3f]",
                fraction(box[0], width), fraction(box[1], height), fraction(box[2], width), fraction(box[3], height)));
            out.append('\n');
            childIndent = indent + 1;
        }
        for (int index = 0; index < node.childCount(); index++) {
            Node child = node.child(index);
            if (child == null) continue;
            try { line(child, childIndent, width, height, out, redact); }
            finally { child.release(); }
        }
    }

    private static double fraction(int value, int size) {
        return Math.min(1.0, Math.max(0.0, (double) value / size));
    }

    /** Cuts at a character boundary, never inside a surrogate pair, and says how much is missing. */
    static String shorten(String value) {
        int length = value.codePointCount(0, value.length());
        if (length <= MAX_TEXT) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_TEXT)) + "…(+" + (length - MAX_TEXT) + " chars)";
    }

    private static String simpleName(String name) {
        return name == null || name.isEmpty() ? "View" : name.substring(name.lastIndexOf('.') + 1);
    }

    /** One line per node: quotes and line breaks are escaped, surrounding blanks dropped. */
    private static String clean(CharSequence value) {
        // String.strip and isBlank need API 33; the current-screen backend runs from API 26.
        if (value == null) return "";
        return value.toString().trim().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n").replace('"', '\'');
    }

    /** Some custom views and web content offer a click only in their action list, without setting the clickable property. */
    public static boolean clickable(AccessibilityNodeInfo node) {
        return node.isClickable() || node.getActionList().contains(AccessibilityAction.ACTION_CLICK);
    }

    public static boolean longClickable(AccessibilityNodeInfo node) {
        return node.isLongClickable() || node.getActionList().contains(AccessibilityAction.ACTION_LONG_CLICK);
    }

    public static boolean scrollable(AccessibilityNodeInfo node) {
        List<AccessibilityAction> actions = node.getActionList();
        return node.isScrollable() || actions.contains(AccessibilityAction.ACTION_SCROLL_FORWARD) ||
            actions.contains(AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    /** The text shown in the node, without the hint that an empty field displays in its place. */
    public static CharSequence text(AccessibilityNodeInfo node) {
        return node.isShowingHintText() ? null : node.getText();
    }

    /** The field's hint; an empty field may report it only as its text. */
    public static CharSequence hint(AccessibilityNodeInfo node) {
        CharSequence hint = node.getHintText();
        return hint == null && node.isShowingHintText() ? node.getText() : hint;
    }

    /** What a text search matches: the shown text, the hint and the description. */
    public static String searchable(AccessibilityNodeInfo node) {
        StringBuilder value = new StringBuilder();
        for (CharSequence part : new CharSequence[]{text(node), hint(node), node.getContentDescription()})
            if (part != null) value.append(part).append('\n');
        return value.toString();
    }

    /** The visible, enabled editable fields under [roots], in order. The caller recycles them. */
    public static List<AccessibilityNodeInfo> editableFields(List<AccessibilityNodeInfo> roots) {
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots) collectFields(root, fields);
        return fields;
    }

    private static void collectFields(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (!node.isVisibleToUser()) return;
        if (node.isEditable() && node.isEnabled()) fields.add(AccessibilityNodeInfo.obtain(node));
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) try { collectFields(child, fields); } finally { child.recycle(); }
        }
    }

    /**
     * Which windows the agent reads, as indices into windows given topmost first. Only application windows are read, from the
     * top down to the one with input focus: a window above it, such as a tooltip that takes no focus, covers part of the page
     * without replacing it, and a window below it is behind it. When no application window has focus, child windows (popups
     * attached to a page) are read down to the first top-level window, so a dialog still hides the page behind it.
     */
    public static int[] readable(boolean[] application, boolean[] focused, boolean[] child) {
        boolean anyFocused = false;
        for (int index = 0; index < application.length; index++) anyFocused |= application[index] && focused[index];
        List<Integer> read = new ArrayList<>();
        for (int index = 0; index < application.length; index++) {
            if (!application[index]) continue;
            read.add(index);
            if (anyFocused ? focused[index] : !child[index]) break;
        }
        int[] result = new int[read.size()];
        for (int index = 0; index < result.length; index++) result[index] = read.get(index);
        return result;
    }

    /** Whether the centre of [node] lies inside any of [above], the windows over its own. */
    public static boolean covered(AccessibilityNodeInfo node, List<Rect> above) {
        if (above.isEmpty()) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        for (Rect window : above) if (window.contains(bounds.centerX(), bounds.centerY())) return true;
        return false;
    }

    private static final class Accessibility implements Node {
        private final AccessibilityNodeInfo node;
        private final boolean owned;
        Accessibility(AccessibilityNodeInfo node, boolean owned) { this.node = node; this.owned = owned; }
        @Override public String className() { CharSequence name = node.getClassName(); return name == null ? null : name.toString(); }
        @Override public CharSequence text() { return WindowOutline.text(node); }
        @Override public CharSequence hint() { return WindowOutline.hint(node); }
        @Override public CharSequence description() { return node.getContentDescription(); }
        @Override public CharSequence state() { return Build.VERSION.SDK_INT >= 30 ? node.getStateDescription() : null; }
        @Override public CharSequence error() { return node.getError(); }
        @Override public boolean visible() { return node.isVisibleToUser(); }
        @Override public boolean enabled() { return node.isEnabled(); }
        @Override public boolean clickable() { return WindowOutline.clickable(node); }
        @Override public boolean longClickable() { return WindowOutline.longClickable(node); }
        @Override public boolean editable() { return node.isEditable(); }
        @Override public boolean scrollable() { return WindowOutline.scrollable(node); }
        @Override public boolean checkable() { return node.isCheckable(); }
        @Override public boolean checked() { return node.isChecked(); }
        @Override public boolean selected() { return node.isSelected(); }
        @Override public boolean focused() { return node.isFocused(); }
        @Override public boolean password() { return node.isPassword(); }
        @Override public int[] bounds() {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            return new int[]{rect.left, rect.top, rect.right, rect.bottom};
        }
        @Override public int childCount() { return node.getChildCount(); }
        @Override public Node child(int index) {
            AccessibilityNodeInfo child = node.getChild(index);
            return child == null ? null : new Accessibility(child, true);
        }
        /** The caller's root stays the caller's to recycle. */
        @Override public void release() { if (owned) node.recycle(); }
    }
}
