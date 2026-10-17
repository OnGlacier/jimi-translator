package com.rltranslator.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/更新后把守护服务拉起来，尽量让读屏服务自己回来。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        GuardService.start(context);
    }
}
