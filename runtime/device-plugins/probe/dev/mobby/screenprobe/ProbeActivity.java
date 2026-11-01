package dev.mobby.screenprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** Isolated UI for real display/input tests; contains no user data and makes no network requests. */
public final class ProbeActivity extends Activity {
    private int count;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);
        TextView value = new TextView(this); value.setText("Count: 0"); content.addView(value);
        Button increment = new Button(this); increment.setText("Increment");
        increment.setOnClickListener(view -> value.setText("Count: " + (++count))); content.addView(increment);
        EditText input = new EditText(this); input.setHint("Probe input"); input.setSingleLine(true); content.addView(input);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 60; i++) { TextView row = new TextView(this); row.setText("Probe row " + i); row.setPadding(0, 16, 0, 16); rows.addView(row); }
        scroll.addView(rows); content.addView(scroll);
        setContentView(content);
    }
}
