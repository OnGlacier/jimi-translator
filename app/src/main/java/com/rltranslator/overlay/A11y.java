package com.rltranslator.overlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * 无障碍服务的自愈工具。
 *
 * ColorOS 等 ROM 在「清理后台」时会把第三方无障碍服务一并关掉，App 自己无法再打开它——
 * 除非拿到 WRITE_SECURE_SETTINGS（一次性 ADB/Shizuku 授权）：
 *
 *   adb shell pm grant com.rltranslator.overlay android.permission.WRITE_SECURE_SETTINGS
 *
 * 授权后每次打开 App 都会自动把服务恢复回去，不用再手动去设置里点。
 */
final class A11y {
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private A11y() {}

    static ComponentName component(Context context) {
        return new ComponentName(context, CaptureService.class);
    }

    /** 系统里当前是否登记了我们的服务。 */
    static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String flat = component(context).flattenToString();
            String shortFlat = component(context).flattenToShortString();
            return enabled.contains(flat) || enabled.contains(shortFlat);
        } catch (Throwable error) {
            return false;
        }
    }

    /** 是否已经拿到自愈权限（一次性授权，重装后需要重新授权）。 */
    static boolean canSelfHeal(Context context) {
        try {
            return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 把我们的服务写回系统设置。
     *
     * @return true 表示写成功了（服务随后会被系统拉起）
     */
    static boolean ensureEnabled(Context context) {
        if (!canSelfHeal(context)) return false;
        try {
            String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
            String current = Settings.Secure.getString(context.getContentResolver(), key);
            String me = component(context).flattenToString();
            String shortMe = component(context).flattenToShortString();
            if (current == null) current = "";
            boolean present = current.contains(me) || current.contains(shortMe);
            if (!present) {
                String next = current.isEmpty() ? me : current + ":" + me;
                Settings.Secure.putString(context.getContentResolver(), key, next);
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 给用户看的一次性命令（在自己电脑或 Shizuku 里执行）。 */
    static String grantCommand(Context context) {
        return "adb shell pm grant " + context.getPackageName() + " " + PERMISSION;
    }
}
