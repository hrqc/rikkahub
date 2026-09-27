package me.rerere.rikkahub.data.mobileagent.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
            if ("inspect_tree_gaps".equals(arguments.getString("operation"))) {
                inspectTreeGaps(result);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("inspect_test_ui".equals(arguments.getString("operation"))) {
                inspectTestUi(result);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("assert_filehelper".equals(arguments.getString("operation"))) {
                assertFilehelper(result);
                finish(Activity.RESULT_OK, result);
                return;
            }
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
            if (arguments != null && "inspect_tree_gaps".equals(arguments.getString("operation"))) {
                result.clear();
                result.putString("tree_gaps", "failed");
                result.putString("reason", "Foreground or structural inspection unavailable");
                finish(Activity.RESULT_CANCELED, result);
                return;
            }
            if (arguments != null && "assert_filehelper".equals(arguments.getString("operation"))) {
                result.clear();
                result.putString("filehelper_message", "failed");
                result.putString("reason", "File helper verification failed");
                finish(Activity.RESULT_CANCELED, result);
                return;
            }
            result.putString("test_input", "failed");
            result.putString("reason", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Structural diagnostics only: this operation never requests node text or descriptions. */
    private void inspectTreeGaps(Bundle result) {
        String expected = arguments.getString("package", "me.rerere.rikkahub.debug");
        if (!List.of("me.rerere.rikkahub.debug", "com.heytap.browser", "com.jingdong.app.mall",
                "com.taobao.taobao", "com.xunmeng.pinduoduo", "com.sankuai.meituan").contains(expected)) {
            throw new IllegalArgumentException("Package is outside test scope");
        }
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if (info == null) throw new IllegalStateException("Automation service unavailable");
        int previousFlags = info.flags;
        ArrayDeque<GapNode> pending = new ArrayDeque<>();
        try {
            // Change only this instrumentation connection, never the installed control service.
            info.flags = 80;
            automation.setServiceInfo(info);
            AccessibilityServiceInfo appliedInfo = automation.getServiceInfo();
            int appliedFlags = appliedInfo == null ? -1 : appliedInfo.flags;
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new IllegalStateException("No active window");
            if (!expected.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                root.recycle();
                throw new IllegalStateException("Expected test app is not active");
            }
            int windowId = root.getWindowId();
            pending.add(new GapNode(root, "/", 0));
            StringBuilder output = new StringBuilder();
            int visits = 0;
            int missing = 0;
            int reported = 0;
            int protectedSkipped = 0;
            int foreignSkipped = 0;
            boolean nodeLimit = false;
            boolean depthLimit = false;
            boolean childLimit = false;
            boolean timeLimit = false;
            long startedAt = SystemClock.elapsedRealtime();
            long deadline = startedAt + 2500;
            while (!pending.isEmpty()) {
                if (visits >= 768) { nodeLimit = true; break; }
                if (SystemClock.elapsedRealtime() >= deadline) { timeLimit = true; break; }
                requireGapWindow(automation, expected, windowId);
                if (SystemClock.elapsedRealtime() >= deadline) { timeLimit = true; break; }
                GapNode current = pending.removeFirst();
                AccessibilityNodeInfo node = current.node;
                visits++;
                try {
                    if (node.getWindowId() != windowId ||
                            !expected.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                        foreignSkipped++;
                        continue;
                    }
                    if (node.isPassword() || (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) {
                        protectedSkipped++;
                        continue;
                    }
                    int children = node.getChildCount();
                    if (current.depth >= 40) {
                        if (children > 0) depthLimit = true;
                        continue;
                    }
                    if (children > 128) childLimit = true;
                    for (int index = 0; index < Math.min(children, 128); index++) {
                        if (SystemClock.elapsedRealtime() >= deadline) { timeLimit = true; break; }
                        if (visits + pending.size() >= 768) { nodeLimit = true; break; }
                        AccessibilityNodeInfo child = android.os.Build.VERSION.SDK_INT >= 33
                                ? node.getChild(index, 0) : node.getChild(index);
                        if (child == null) {
                            missing++;
                            if (reported < 20) {
                                Rect bounds = new Rect();
                                node.getBoundsInScreen(bounds);
                                output.append("parent=").append(current.path)
                                    .append(" class=").append(gapMetadata(node.getClassName(), 160))
                                    .append(" viewId=").append(gapMetadata(node.getViewIdResourceName(), 256))
                                    .append(" bounds=").append(bounds.toShortString())
                                    .append(" visible=").append(node.isVisibleToUser())
                                    .append(" childCount=").append(children)
                                    .append(" index=").append(index).append('\n');
                                reported++;
                            }
                        } else {
                            String path = "/".equals(current.path) ? "/" + index : current.path + "/" + index;
                            pending.addLast(new GapNode(child, path, current.depth + 1));
                        }
                    }
                } finally { node.recycle(); }
            }
            requireGapWindow(automation, expected, windowId);
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            if (elapsed >= 2500) timeLimit = true;
            result.putString("tree_gaps", "inspected");
            result.putString("gaps_b64", Base64.encodeToString(output.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            result.putInt("visits", visits);
            result.putInt("missing", missing);
            result.putInt("reported", reported);
            result.putInt("protected_skipped", protectedSkipped);
            result.putInt("foreign_skipped", foreignSkipped);
            result.putLong("elapsed_ms", elapsed);
            result.putInt("requested_automation_flags", 80);
            result.putInt("automation_flags", appliedFlags);
            result.putString("traversal_budget", "nodes=768,depth=40,time_ms=2500,children=128,gap_records=20");
            result.putBoolean("node_limit", nodeLimit);
            result.putBoolean("depth_limit", depthLimit);
            result.putBoolean("child_limit", childLimit);
            result.putBoolean("time_limit", timeLimit);
            result.putBoolean("partial", missing > 0 || nodeLimit || depthLimit || childLimit || timeLimit || foreignSkipped > 0 || protectedSkipped > 0);
        } finally {
            while (!pending.isEmpty()) pending.removeFirst().node.recycle();
            info.flags = previousFlags;
            automation.setServiceInfo(info);
        }
    }

    private static void requireGapWindow(UiAutomation automation, String expected, int windowId) {
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (active == null) throw new IllegalStateException("Active window unavailable");
        try {
            if (active.getWindowId() != windowId ||
                    !expected.contentEquals(active.getPackageName() == null ? "" : active.getPackageName())) {
                throw new IllegalStateException("Foreground changed");
            }
        } finally { active.recycle(); }
    }

    private static String gapMetadata(CharSequence value, int limit) {
        if (value == null) return "";
        String bounded = value.subSequence(0, Math.min(value.length(), limit)).toString();
        return bounded.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static final class GapNode {
        final AccessibilityNodeInfo node;
        final String path;
        final int depth;
        GapNode(AccessibilityNodeInfo node, String path, int depth) {
            this.node = node;
            this.path = path;
            this.depth = depth;
        }
    }

    /** Local USB diagnostics for this task's foreground apps, without suppressing accessibility. */
    private void inspectTestUi(Bundle result) {
        String expected = arguments.getString("package", "me.rerere.rikkahub.debug");
        if (!List.of("me.rerere.rikkahub.debug", "com.heytap.browser", "com.jingdong.app.mall",
                "com.taobao.taobao", "com.xunmeng.pinduoduo", "com.sankuai.meituan").contains(expected)) {
            throw new IllegalArgumentException("Package is outside test scope");
        }
        AccessibilityNodeInfo root = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("No active window");
        if (!expected.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle();
            throw new IllegalStateException("Expected test app is not active");
        }
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        StringBuilder output = new StringBuilder();
        int visits = 0;
        long deadline = SystemClock.elapsedRealtime() + 2500;
        try {
            while (!pending.isEmpty() && visits++ < 768 && output.length() < 18000 && SystemClock.elapsedRealtime() < deadline) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (!expected.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) continue;
                    if (node.isPassword() || (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) continue;
                    if (node.isVisibleToUser()) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        CharSequence text = node.isEditable() ? null : node.getText();
                        CharSequence description = node.isEditable() ? null : node.getContentDescription();
                        if (text != null || description != null || node.isClickable() || node.isEditable() || node.isScrollable()) {
                            output.append(bounds.toShortString()).append(" click=").append(node.isClickable())
                                .append(" edit=").append(node.isEditable()).append(" scroll=").append(node.isScrollable()).append(" ");
                            if (text != null) output.append(text.subSequence(0, Math.min(text.length(), 900)));
                            if (description != null) output.append(" [").append(description.subSequence(0, Math.min(description.length(), 400))).append("]");
                            output.append('\n');
                        }
                    }
                    for (int child = 0; child < Math.min(node.getChildCount(), 128) && pending.size() < 768; child++) {
                        AccessibilityNodeInfo next = node.getChild(child);
                        if (next != null) pending.addLast(next);
                    }
                } finally { node.recycle(); }
            }
            result.putBoolean("partial", !pending.isEmpty());
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        result.putString("ui_b64", Base64.encodeToString(output.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void assertFilehelper(Bundle result) {
        String encoded = arguments.getString("expected_b64", "");
        if (encoded.length() > 1024) throw new IllegalArgumentException("Invalid synthetic text");
        String expected = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        if (!expected.startsWith("Mobile Agent V1 测试") || expected.codePointCount(0, expected.length()) > 160) {
            throw new IllegalArgumentException("Invalid synthetic text");
        }
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("Verification unavailable");
        if (!isWechatNode(root)) {
            root.recycle();
            throw new IllegalStateException("Verification unavailable");
        }
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        if (window.isEmpty()) {
            root.recycle();
            throw new IllegalStateException("Verification unavailable");
        }
        ArrayDeque<VerificationNode> pending = new ArrayDeque<>();
        pending.add(new VerificationNode(root, false, 0));
        List<Rect> titles = new ArrayList<>();
        List<Rect> backButtons = new ArrayList<>();
        int matches = 0;
        int visited = 0;
        try {
            while (!pending.isEmpty()) {
                if (++visited > 512) throw new IllegalStateException("Verification unavailable");
                VerificationNode current = pending.removeFirst();
                AccessibilityNodeInfo node = current.node;
                try {
                    if (!isWechatNode(node)) continue;
                    boolean editable = current.editableAncestor || node.isEditable() || node.isPassword() ||
                            "android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName());
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    boolean visible = node.isVisibleToUser() && !bounds.isEmpty() && Rect.intersects(bounds, window);
                    if (visible && !editable) {
                        CharSequence text = node.getText();
                        if (expected.contentEquals(text == null ? "" : text)) matches++;
                        // Only an actual small text node can be the toolbar title. A root or
                        // container's aggregated contentDescription is never title evidence.
                        boolean toolbarNode = current.depth > 0 && node.getChildCount() == 0 &&
                                bounds.height() <= window.height() / 8 && bounds.width() <= window.width() * 4 / 5 &&
                                bounds.centerY() < window.top + window.height() / 5;
                        if (toolbarNode && "android.widget.TextView".contentEquals(node.getClassName() == null ? "" : node.getClassName()) &&
                                "文件传输助手".contentEquals(text == null ? "" : text)) {
                            titles.add(bounds);
                        }
                        // A nearby back affordance distinguishes a chat toolbar from the
                        // same contact name and message preview on the conversation list.
                        if (toolbarNode && "返回".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
                            backButtons.add(bounds);
                        }
                    }
                    int children = node.getChildCount();
                    if (children > 64 || (children > 0 && current.depth >= 32)) throw new IllegalStateException("Verification unavailable");
                    for (int child = 0; child < children; child++) {
                        if (pending.size() >= 512) throw new IllegalStateException("Verification unavailable");
                        AccessibilityNodeInfo entry = node.getChild(child);
                        if (entry != null) pending.addLast(new VerificationNode(entry, editable, current.depth + 1));
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!pending.isEmpty()) pending.removeFirst().node.recycle(); }
        boolean titleVerified = false;
        for (Rect title : titles) {
            for (Rect back : backButtons) {
                if (back.centerX() < title.left && Math.abs(back.centerY() - title.centerY()) <= Math.max(back.height(), title.height())) {
                    titleVerified = true;
                }
            }
        }
        if (!titleVerified || matches < 1) throw new IllegalStateException("Verification unavailable");
        result.putString("filehelper_message", "verified");
        result.putInt("matches", matches);
    }

    private static boolean isWechatNode(AccessibilityNodeInfo node) {
        return "com.tencent.mm".contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
    }

    private static final class VerificationNode {
        final AccessibilityNodeInfo node;
        final boolean editableAncestor;
        final int depth;
        VerificationNode(AccessibilityNodeInfo node, boolean editableAncestor, int depth) {
            this.node = node;
            this.editableAncestor = editableAncestor;
            this.depth = depth;
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
