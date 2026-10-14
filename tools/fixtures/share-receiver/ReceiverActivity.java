package com.nickwoluff.wearfiles.qareceiver;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import java.io.InputStream;
import java.security.MessageDigest;

// 不请求存储权限，验证接收进程只能通过分享授予的 URI 权限读取原文件。
public class ReceiverActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String result;
        try {
            Intent intent = getIntent();
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (!Intent.ACTION_SEND.equals(intent.getAction()) || uri == null ||
                    intent.getClipData() == null ||
                    !uri.equals(intent.getClipData().getItemAt(0).getUri()) ||
                    (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                throw new IllegalStateException("缺少分享内容或读取授权");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int size = 0;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, length);
                    size += length;
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
            result = "PASS component=" + getComponentName().getShortClassName() +
                    " mime=" + intent.getType() + " bytes=" + size + " sha256=" + hash;
        } catch (Exception error) {
            result = "FAIL " + error;
        }
        Log.i("AowuShareQA", result);
        TextView text = new TextView(this);
        text.setText(result.startsWith("PASS") ? "已读取分享文件\n验证通过" : result);
        text.setTextSize(15);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
    }
}
