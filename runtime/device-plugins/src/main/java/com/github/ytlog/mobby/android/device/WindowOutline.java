package com.github.ytlog.mobby.android.device;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.UnaryOperator;

/**
 * The agent's text view of one window, shared by the current-screen and virtual-display backends.
 *
 * <p>Only what the user can see is listed. Subtrees that are not visible to the user are skipped, and containers that carry
 * no text, description or action are walked through without a line of their own, so the line budget goes to content
 * instead of layout wrappers. A deep tree is read to its leaves. When a budget runs out the outline says so in its last
 * line instead of ending silently.
 */
public final class WindowOutline {
    /** Lines given to the agent. */
    public static final int MAX_LINES = 300;
    /** Nodes read for one outline or search; each read is an IPC to the app. */
    public static final int MAX_VISITED = 2000;
    /** A guard against runaway recursion, far below any stack limit and above real view trees. */
    public static final int MAX_DEPTH = 80;
    static final int MAX_LABEL = 80;
    static final String TRUNCATED = "[truncated: more on-screen controls were not listed]";

    private WindowOutline() { }

    /** The parts of an accessibility node the outline reads. A child returned by {@link #child} is released by the caller. */
    public interface Node {
        String className();
        CharSequence text();
        CharSequence description();
        boolean visible();
        boolean clickable();
        boolean editable();
        boolean scrollable();
        boolean checkable();
        boolean checked();
        boolean focused();
        boolean password();
        int childCount();
        Node child(int index);
        void release();
    }

    public static String of(AccessibilityNodeInfo root, UnaryOperator<String> redact) {
        return of(new Accessibility(root, false), redact);
    }

    static String of(Node root, UnaryOperator<String> redact) {
        StringBuilder out = new StringBuilder();
        int[] budget = {0, 0}; // visited, lines
        boolean complete = line(root, 0, 0, true, out, budget, redact);
        if (!complete) out.append(TRUNCATED).append('\n');
        return out.toString();
    }

    /** Returns false once any budget ran out. The root always gets a line so the window is named. */
    private static boolean line(Node node, int depth, int indent, boolean root, StringBuilder out, int[] budget,
        UnaryOperator<String> redact) {
        if (budget[0] >= MAX_VISITED || depth > MAX_DEPTH) return false;
        budget[0]++;
        if (!root && !node.visible()) return true;
        String label = node.password() ? "[secure]" : redact.apply(label(node));
        boolean listed = root || !label.isEmpty() || node.clickable() || node.editable() || node.scrollable() || node.checkable();
        int childIndent = indent;
        if (listed) {
            if (budget[1] >= MAX_LINES) return false;
            budget[1]++;
            String name = node.className();
            for (int space = 0; space < indent; space++) out.append("  ");
            out.append(name == null || name.isEmpty() ? "View" : name.substring(name.lastIndexOf('.') + 1));
            if (!label.isEmpty()) out.append(" \"").append(label.substring(0, Math.min(label.length(), MAX_LABEL)).replace('"', '\'')).append('"');
            if (node.clickable()) out.append(" clickable");
            if (node.editable()) out.append(" editable");
            if (node.scrollable()) out.append(" scrollable");
            if (node.checkable()) out.append(node.checked() ? " checked" : " unchecked");
            if (node.focused()) out.append(" focused");
            out.append('\n');
            childIndent = indent + 1;
        }
        for (int index = 0; index < node.childCount(); index++) {
            Node child = node.child(index);
            if (child == null) continue;
            try { if (!line(child, depth + 1, childIndent, false, out, budget, redact)) return false; }
            finally { child.release(); }
        }
        return true;
    }

    private static String label(Node node) {
        CharSequence text = node.text();
        // String.strip and isBlank need API 33; the current-screen backend runs from API 26.
        String value = text == null ? "" : text.toString().trim();
        if (!value.isEmpty()) return value;
        CharSequence description = node.description();
        return description == null ? "" : description.toString().trim();
    }

    private static final class Accessibility implements Node {
        private final AccessibilityNodeInfo node;
        private final boolean owned;
        Accessibility(AccessibilityNodeInfo node, boolean owned) { this.node = node; this.owned = owned; }
        @Override public String className() { CharSequence name = node.getClassName(); return name == null ? null : name.toString(); }
        @Override public CharSequence text() { return node.getText(); }
        @Override public CharSequence description() { return node.getContentDescription(); }
        @Override public boolean visible() { return node.isVisibleToUser(); }
        @Override public boolean clickable() { return node.isClickable(); }
        @Override public boolean editable() { return node.isEditable(); }
        @Override public boolean scrollable() { return node.isScrollable(); }
        @Override public boolean checkable() { return node.isCheckable(); }
        @Override public boolean checked() { return node.isChecked(); }
        @Override public boolean focused() { return node.isFocused(); }
        @Override public boolean password() { return node.isPassword(); }
        @Override public int childCount() { return node.getChildCount(); }
        @Override public Node child(int index) {
            AccessibilityNodeInfo child = node.getChild(index);
            return child == null ? null : new Accessibility(child, true);
        }
        /** The caller's root stays the caller's to recycle. */
        @Override public void release() { if (owned) node.recycle(); }
    }
}
