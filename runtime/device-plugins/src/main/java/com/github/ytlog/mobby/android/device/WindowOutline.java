package com.github.ytlog.mobby.android.device;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.UnaryOperator;

/**
 * The agent's text view of one window, shared by the current-screen and virtual-display backends.
 *
 * <p>The whole tree is read, without a node count or depth limit. A node gets a line when it is on screen and has text, a
 * description, an image or an action (click, long click, edit, scroll, check). Nodes that are not visible to the user are
 * skipped with their subtrees, as {@code uiautomator dump} does; containers with none of the above are walked through without
 * a line. Each line is indented by its listed ancestors. Line breaks are written as {@code \n}. A text or description longer
 * than {@link #MAX_TEXT} characters is cut there and says how many characters were left out.
 */
public final class WindowOutline {
    /** Characters of one text or description; an article body or chat history in one node would otherwise flood the outline. */
    public static final int MAX_TEXT = 500;

    private WindowOutline() { }

    /** The parts of an accessibility node the outline reads. A child returned by {@link #child} is released by the caller. */
    public interface Node {
        String className();
        CharSequence text();
        CharSequence description();
        boolean visible();
        boolean clickable();
        boolean longClickable();
        boolean editable();
        boolean scrollable();
        boolean checkable();
        boolean checked();
        boolean selected();
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
        line(root, 0, out, redact);
        return out.toString();
    }

    /** Recursion is as deep as the app's own view tree, which the app measures and draws recursively on a smaller stack. */
    private static void line(Node node, int indent, StringBuilder out, UnaryOperator<String> redact) {
        if (!node.visible()) return;
        // Redaction runs on the whole value first, so a cut never leaves part of a private value behind.
        String text = node.password() ? "[secure]" : shorten(redact.apply(clean(node.text())));
        String description = shorten(redact.apply(clean(node.description())));
        String name = simpleName(node.className());
        boolean image = name.contains("Image");
        boolean action = node.clickable() || node.longClickable() || node.editable() || node.scrollable() || node.checkable();
        int childIndent = indent;
        if (!text.isEmpty() || !description.isEmpty() || image || action) {
            for (int space = 0; space < indent; space++) out.append("  ");
            out.append(name);
            if (!text.isEmpty()) out.append(" \"").append(text).append('"');
            if (!description.isEmpty() && !description.equals(text)) out.append(" desc=\"").append(description).append('"');
            if (node.clickable()) out.append(" clickable");
            if (node.longClickable()) out.append(" long-clickable");
            if (node.editable()) out.append(" editable");
            if (node.scrollable()) out.append(" scrollable");
            if (node.checkable()) out.append(node.checked() ? " checked" : " unchecked");
            if (node.selected()) out.append(" selected");
            if (node.focused()) out.append(" focused");
            out.append('\n');
            childIndent = indent + 1;
        }
        for (int index = 0; index < node.childCount(); index++) {
            Node child = node.child(index);
            if (child == null) continue;
            try { line(child, childIndent, out, redact); }
            finally { child.release(); }
        }
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

    private static final class Accessibility implements Node {
        private final AccessibilityNodeInfo node;
        private final boolean owned;
        Accessibility(AccessibilityNodeInfo node, boolean owned) { this.node = node; this.owned = owned; }
        @Override public String className() { CharSequence name = node.getClassName(); return name == null ? null : name.toString(); }
        @Override public CharSequence text() { return node.getText(); }
        @Override public CharSequence description() { return node.getContentDescription(); }
        @Override public boolean visible() { return node.isVisibleToUser(); }
        @Override public boolean clickable() { return node.isClickable(); }
        @Override public boolean longClickable() { return node.isLongClickable(); }
        @Override public boolean editable() { return node.isEditable(); }
        @Override public boolean scrollable() { return node.isScrollable(); }
        @Override public boolean checkable() { return node.isCheckable(); }
        @Override public boolean checked() { return node.isChecked(); }
        @Override public boolean selected() { return node.isSelected(); }
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
