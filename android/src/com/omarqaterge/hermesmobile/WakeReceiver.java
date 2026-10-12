package com.omarqaterge.hermesmobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The cron alarm (HermesService.scheduleWake): a scheduled job is due, so keep the phone awake for it. */
public class WakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        HermesService s = HermesService.instance;
        if (s != null) {
            s.wakeForCron();
            return;
        }
        try {
            ctx.startForegroundService(new Intent(ctx, HermesService.class).setAction(HermesService.ACTION_WAKE));
        } catch (Exception ignored) {
        }
    }
}
