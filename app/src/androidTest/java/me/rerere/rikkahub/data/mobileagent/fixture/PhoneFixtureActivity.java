package me.rerere.rikkahub.data.mobileagent.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Runs in the separate test APK process, which does not contain the main APK's Kotlin runtime. */
public class PhoneFixtureActivity extends Activity {
    private int clicks;
    private int longClicks;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setFocusableInTouchMode(true);
        root.addView(label("手机控制测试首页"));

        final TextView clickStatus = label("点击次数：0");
        final TextView longClickStatus = label("长按次数：0");
        Button clickButton = new Button(this);
        clickButton.setText("点击测试");
        clickButton.setContentDescription("点击测试");
        clickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clickStatus.setText("点击次数：" + (++clicks));
            }
        });
        clickButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                longClickStatus.setText("长按次数：" + (++longClicks));
                return true;
            }
        });
        root.addView(clickButton);
        root.addView(clickStatus);
        root.addView(longClickStatus);

        EditText input = new EditText(this);
        input.setHint("测试输入框");
        input.setContentDescription("测试输入框");
        input.setSingleLine(true);
        // Test ACTION_SET_TEXT without opening an out-of-scope input-method window.
        input.setShowSoftInputOnFocus(false);
        root.addView(input);

        Button secondPage = new Button(this);
        secondPage.setText("打开测试第二页");
        secondPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(getPackageName(), PhoneFixtureSecondActivity.class.getName()));
                startActivity(intent);
            }
        });
        root.addView(secondPage);

        final TextView scrollStatus = label("滚动位置：0");
        root.addView(scrollStatus);
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("测试滚动区域");
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < 24; index++) {
            rows.addView(label("测试列表行 " + (index + 1)), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        }
        scroll.addView(rows);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View view, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                scrollStatus.setText("滚动位置：" + scrollY);
            }
        });
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        root.requestFocus();
    }

    private TextView label(String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(16f);
        label.setPadding(0, dp(4), 0, dp(4));
        return label;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
