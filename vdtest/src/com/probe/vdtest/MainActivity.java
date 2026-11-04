package com.probe.vdtest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {

    static final String TAG = "VDTEST";
    static final int REQ_CAPTURE = 1001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "--- MainActivity onCreate");
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "no MediaProjectionManager");
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        Log.i(TAG, "onActivityResult req=" + req + " resultCode=" + resultCode);
        if (req != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Log.e(TAG, "consent denied");
            finish();
            return;
        }
        Intent svc = new Intent(this, CapService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", data);
        startForegroundService(svc);
        Log.i(TAG, "CapService started");
        moveTaskToBack(true);
    }
}
