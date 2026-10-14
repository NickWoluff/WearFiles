package com.nickwoluff.wearfiles.qareceiver;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

// 仅核对本轮复制的命令，不打印其他剪贴板内容。
public class ClipboardActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("正在检查三条授权命令");
        setContentView(view);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) return;
        String packageName = getIntent().getStringExtra("expectedPackage");
        if (packageName == null) packageName = "com.nickwoluff.wearfiles.permissionqa";
        String expected = "adb shell pm grant " + packageName + " android.permission.READ_EXTERNAL_STORAGE\n" +
                "adb shell pm grant " + packageName + " android.permission.WRITE_EXTERNAL_STORAGE\n" +
                "adb shell appops set " + packageName + " MANAGE_EXTERNAL_STORAGE allow";
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = manager.getPrimaryClip();
        boolean matched = clip != null && clip.getItemCount() == 1 &&
                "嗷呜文件 ADB 授权".contentEquals(clip.getDescription().getLabel()) &&
                expected.contentEquals(clip.getItemAt(0).getText());
        Log.i("AowuShareQA", (matched ? "PASS" : "FAIL") + " clipboard-three-commands package=" + packageName);
    }
}
