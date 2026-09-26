package me.rerere.rikkahub.data.mobileagent.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native-only second page in the same isolated test APK. */
public class PhoneFixtureSecondActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("测试第二页");
        title.setTextSize(24f);
        root.addView(title);

        Button back = new Button(this);
        back.setText("返回测试首页");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        root.addView(back);
        setContentView(root);
    }
}
