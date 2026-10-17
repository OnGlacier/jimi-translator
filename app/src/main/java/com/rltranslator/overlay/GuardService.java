package com.rltranslator.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 守护服务：独立于读屏服务的前台进程，负责「读屏被系统关掉 → 自动恢复」。
 *
 * 原理：
 *  1. 前台服务 + 常驻通知，让 App 进程在 ColorOS 的「清理后台」里不容易被杀；
 *  2. 每 15 秒检查一次无障碍服务是否还在，不在就写回系统设置（需要一次性授权
 *     WRITE_SECURE_SETTINGS，见 A11y.grantCommand）；
 *  3. START_STICKY + 开机广播，被系统杀掉后尽量自己回来。
 *
 * 没有授权时它只能保活和提示，无法绕过 Android 的安全限制。
 */
public class GuardService extends Service {

    private static final String CHANNEL_ID = "jimi_guard";
    private static final int NOTIFICATION_ID = 0x6A6E;
    private static final long CHECK_INTERVAL_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            try {
                if (!A11y.isEnabled(GuardService.this)) {
                    if (A11y.canSelfHeal(GuardService.this)) {
                        A11y.ensureEnabled(GuardService.this);
                    } else {
                        notifyState(true);
                    }
                } else {
                    notifyState(false);
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification(false));
        handler.postDelayed(check, 800);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyState(boolean broken) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.notify(NOTIFICATION_ID, buildNotification(broken));
    }

    private Notification buildNotification(boolean broken) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "读屏守护", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持读屏服务可用");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        boolean granted = A11y.canSelfHeal(this);
        String title = broken ? "读屏服务被系统关掉了" : "基米翻译守护中";
        String text;
        if (!granted) {
            text = "授权一次即可自动恢复：" + A11y.grantCommand(this);
        } else if (broken) {
            text = "正在自动恢复…";
        } else {
            text = "读屏服务正常；被系统关掉时会自动打开";
        }
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getActivity(this, 1, intent, flags);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pending);
        return builder.build();
    }

    static void start(android.content.Context context) {
        try {
            Intent intent = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }
}
