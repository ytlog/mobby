package com.github.ytlog.mobby.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.*;

/** Test APK only. No model calls, user files or account state. */
@android.annotation.TargetApi(33)
public final class ScreenControlFixtureActivity extends Activity {
    static final String CLEAR_INPUT_FOCUS = "com.github.ytlog.mobby.android.test.CLEAR_INPUT_FOCUS";
    static final String CHECK_MANUAL_FOCUS = "com.github.ytlog.mobby.android.test.CHECK_MANUAL_FOCUS";
    static final String CHECK_MANUAL_INPUT = "com.github.ytlog.mobby.android.test.CHECK_MANUAL_INPUT";
    static final String CHECK_MANUAL_SCROLL = "com.github.ytlog.mobby.android.test.CHECK_MANUAL_SCROLL";
    static final String CHECK_MANUAL_SLIDER = "com.github.ytlog.mobby.android.test.CHECK_MANUAL_SLIDER";
    static final String MANUAL_TEXT = "mobby-manual-code-fixture";
    private int count;
    private BroadcastReceiver inputProbe;
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
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription("Probe slider");
        slider.setProgress(20);
        content.addView(slider);
        Button secure = new Button(this); secure.setText("Secure field");
        EditText code = new EditText(this); code.setHint("Probe code");
        code.setSingleLine(true); code.setVisibility(android.view.View.GONE);
        code.setShowSoftInputOnFocus(false);
        secure.setOnClickListener(view -> {
            code.setVisibility(android.view.View.VISIBLE);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.requestFocus();
        });
        content.addView(secure);
        content.addView(code);
        TextView geometry = new TextView(this); content.addView(geometry);
        TextView focusGeometry = new TextView(this); content.addView(focusGeometry);
        increment.post(() -> {
            int[] location = new int[2]; increment.getLocationOnScreen(location);
            int[] field = new int[2]; input.getLocationOnScreen(field);
            int[] track = new int[2]; slider.getLocationOnScreen(track);
            int[] focusButton = new int[2]; secure.getLocationOnScreen(focusButton);
            DisplayMetrics metrics = new DisplayMetrics(); getDisplay().getRealMetrics(metrics);
            geometry.setText("Button center: " + (location[0] + increment.getWidth() / 2) + "," +
                (location[1] + increment.getHeight() / 2) + "/" + metrics.widthPixels + "," + metrics.heightPixels +
                " Input center: " + (field[0] + input.getWidth() / 2) + "," + (field[1] + input.getHeight() / 2) +
                " Slider track: " + (track[0] + slider.getPaddingLeft()) + "," + (track[1] + slider.getHeight() / 2) +
                "," + (slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight()));
            focusGeometry.setText("Focus button: " + (focusButton[0] + secure.getWidth() / 2) + "," + (focusButton[1] + secure.getHeight() / 2));
        });
        TextView scrollPosition = new TextView(this); scrollPosition.setText("Scroll position: 0"); content.addView(scrollPosition);
        ScrollView scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> scrollPosition.setText("Scroll position: " + y));
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 60; i++) { TextView row = new TextView(this); row.setText("Probe row " + i); row.setPadding(0, 16, 0, 16); rows.addView(row); }
        scroll.addView(rows); content.addView(scroll);
        setContentView(content);
        content.requestFocus();
        inputProbe = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean passed = false;
                if (CLEAR_INPUT_FOCUS.equals(intent.getAction())) {
                    content.requestFocus();
                    passed = !input.hasFocus();
                } else if (CHECK_MANUAL_FOCUS.equals(intent.getAction())) {
                    passed = input.hasFocus();
                } else if (CHECK_MANUAL_INPUT.equals(intent.getAction())) {
                    passed = MANUAL_TEXT.contentEquals(input.getText());
                } else if (CHECK_MANUAL_SCROLL.equals(intent.getAction())) {
                    passed = scroll.getScrollY() > intent.getIntExtra("previousY", Integer.MAX_VALUE);
                } else if (CHECK_MANUAL_SLIDER.equals(intent.getAction())) {
                    passed = Math.abs(slider.getProgress() - intent.getIntExtra("progress", -100)) <= 1;
                }
                setResultCode(passed ? RESULT_OK : RESULT_CANCELED);
            }
        };
        IntentFilter filter = new IntentFilter(CLEAR_INPUT_FOCUS);
        filter.addAction(CHECK_MANUAL_FOCUS);
        filter.addAction(CHECK_MANUAL_INPUT);
        filter.addAction(CHECK_MANUAL_SCROLL);
        filter.addAction(CHECK_MANUAL_SLIDER);
        registerReceiver(inputProbe, filter, Context.RECEIVER_EXPORTED);
    }
    @Override protected void onDestroy() {
        if (inputProbe != null) unregisterReceiver(inputProbe);
        super.onDestroy();
    }
    public static final class Detail extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            TextView text = new TextView(this); text.setText("Probe detail"); setContentView(text);
        }
    }
}
