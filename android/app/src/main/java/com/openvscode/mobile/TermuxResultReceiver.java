package com.openvscode.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported PendingIntent target; persists results even if the Activity was recreated. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TermuxBridge.receiveResult(context, intent);
    }
}
