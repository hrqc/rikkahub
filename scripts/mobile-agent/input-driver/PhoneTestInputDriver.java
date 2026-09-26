package me.rerere.rikkahub.data.mobileagent.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

/** USB test APK only. Inserts bounded test text into an empty, focused field of the main debug app. */
public final class PhoneTestInputDriver extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("stop_fixture_notification".equals(arguments.getString("operation"))) {
                stopFixtureNotification();
                result.putString("notification_stop", "clicked");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("observe_fixture".equals(arguments.getString("operation"))) {
                observeFixture(result);
                finish(Activity.RESULT_OK, result);
                return;
            }
            String encoded = arguments.getString("text_b64", "");
            if (encoded.length() > 8192) throw new IllegalArgumentException("Test text too long");
            String value = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            if (value.isEmpty() || value.length() > 2000) throw new IllegalArgumentException("Invalid test text length");
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; attempt < 20 && root == null; attempt++) {
                root = automation.getRootInActiveWindow();
                if (root == null) SystemClock.sleep(100);
            }
            if (root == null) throw new IllegalStateException("No active app window");
            ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            boolean written = false;
            int visited = 0;
            try {
                while (!pending.isEmpty() && visited++ < 256) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    try {
                        if (!"me.rerere.rikkahub.debug".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) continue;
                        if (node.isEditable() && node.isFocused() && !node.isPassword()) {
                            CharSequence existing = node.getText();
                            if (existing != null && existing.length() > 0) throw new IllegalStateException("Test field must be empty");
                            Bundle action = new Bundle();
                            action.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                            written = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
                            if (written) {
                                node.refresh();
                                written = value.contentEquals(node.getText() == null ? "" : node.getText());
                            }
                            break;
                        }
                        for (int child = 0; child < node.getChildCount() && pending.size() < 256; child++) {
                            AccessibilityNodeInfo entry = node.getChild(child);
                            if (entry != null) pending.addLast(entry);
                        }
                    } finally {
                        node.recycle();
                    }
                }
            } finally {
                while (!pending.isEmpty()) pending.removeFirst().recycle();
            }
            if (!written) throw new IllegalStateException("Focused empty test field was not set");
            result.putString("test_input", "verified");
            result.putInt("characters", value.length());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("test_input", "failed");
            result.putString("reason", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void observeFixture(Bundle result) {
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null || !"me.rerere.rikkahub.debug.test".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            if (root != null) root.recycle();
            throw new IllegalStateException("Synthetic fixture is not the active window");
        }
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        StringBuilder text = new StringBuilder();
        int visited = 0;
        try {
            while (!pending.isEmpty() && visited++ < 256 && text.length() < 4000) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (!"me.rerere.rikkahub.debug.test".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) continue;
                    if (node.isVisibleToUser() && !node.isPassword() && node.getText() != null) {
                        text.append(node.getText()).append('\n');
                    }
                    for (int child = 0; child < node.getChildCount() && pending.size() < 256; child++) {
                        AccessibilityNodeInfo entry = node.getChild(child);
                        if (entry != null) pending.addLast(entry);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        result.putString("fixture_text_b64", Base64.encodeToString(text.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void stopFixtureNotification() {
        AccessibilityNodeInfo root = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("No notification window");
        try {
            if (!"com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) throw new IllegalStateException("Notification panel is not active");
            List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText("手机控制会话已开启");
            try {
                if (titles.size() != 1) throw new IllegalStateException("Expected one phone control notice");
                AccessibilityNodeInfo scope = titles.get(0).getParent();
                for (int depth = 0; scope != null && depth < 5; depth++) {
                    AccessibilityNodeInfo next = null;
                    try {
                        List<AccessibilityNodeInfo> targets = scope.findAccessibilityNodeInfosByText("me.rerere.rikkahub.debug.test");
                        boolean fixture = !targets.isEmpty();
                        for (AccessibilityNodeInfo target : targets) target.recycle();
                        List<AccessibilityNodeInfo> buttons = scope.findAccessibilityNodeInfosByText("STOP");
                        try {
                            if (fixture && buttons.size() == 1 && "STOP".contentEquals(buttons.get(0).getText())) {
                                if (buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                                AccessibilityNodeInfo parent = buttons.get(0).getParent();
                                if (parent != null) try { if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; } finally { parent.recycle(); }
                            }
                        } finally { for (AccessibilityNodeInfo button : buttons) button.recycle(); }
                        next = scope.getParent();
                    } finally { scope.recycle(); }
                    scope = next;
                }
                if (scope != null) scope.recycle();
            } finally { for (AccessibilityNodeInfo title : titles) title.recycle(); }
            throw new IllegalStateException("Fixture notification STOP action is not visible");
        } finally { root.recycle(); }
    }
}
