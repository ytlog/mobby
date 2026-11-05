package com.github.ytlog.mobby.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.*;

/** Test APK only. No model calls, user files or account state. */
@android.annotation.TargetApi(33)
public final class ScreenControlFixtureActivity extends Activity {
    private int count;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.setPadding(24, 24, 24, 24);
        TextView value = new TextView(this); value.setText("Count: 0"); content.addView(value);
        Button increment = new Button(this); increment.setText("Increment");
        increment.setOnClickListener(view -> value.setText("Count: " + (++count))); content.addView(increment);
        EditText input = new EditText(this); input.setHint("Probe input"); input.setContentDescription("Probe input");
        input.setSingleLine(true); input.setShowSoftInputOnFocus(false); content.addView(input);
        Button detail = new Button(this); detail.setText("Open detail");
        detail.setOnClickListener(view -> startActivity(new android.content.Intent(this, Detail.class))); content.addView(detail);
        Button secure = new Button(this); secure.setText("Secure field");
        secure.setOnClickListener(view -> {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.requestFocus();
        });
        content.addView(secure);
        TextView geometry = new TextView(this); content.addView(geometry);
        increment.post(() -> {
            int[] location = new int[2]; increment.getLocationOnScreen(location);
            DisplayMetrics metrics = new DisplayMetrics(); getDisplay().getRealMetrics(metrics);
            geometry.setText("Button center: " + (location[0] + increment.getWidth() / 2) + "," +
                (location[1] + increment.getHeight() / 2) + "/" + metrics.widthPixels + "," + metrics.heightPixels);
        });
        TextView scrollPosition = new TextView(this); scrollPosition.setText("Scroll position: 0"); content.addView(scrollPosition);
        ScrollView scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> scrollPosition.setText("Scroll position: " + y));
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 60; i++) { TextView row = new TextView(this); row.setText("Probe row " + i); row.setPadding(0, 16, 0, 16); rows.addView(row); }
        scroll.addView(rows); content.addView(scroll);
        setContentView(content);
        content.requestFocus();
    }
    public static final class Detail extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            TextView text = new TextView(this); text.setText("Probe detail"); setContentView(text);
        }
    }
}
