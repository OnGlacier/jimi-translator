package com.rltranslator.overlay;

import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku（或 Sui）以 shell 身份执行一次授权，让 App 能自己写回无障碍开关。
 *
 * 流程：点按钮 → Shizuku 弹授权框 → 允许 → 执行
 *   pm grant <包名> android.permission.WRITE_SECURE_SETTINGS
 *
 * Shizuku 没装 / 没启动时全部方法安全返回 false，不影响其他功能。
 */
final class ShizukuHelper {

    static final int REQUEST_CODE = 0x5A1C;

    private ShizukuHelper() {}

    /** Shizuku 服务是否在跑。 */
    static boolean isReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable error) {
            return false;
        }
    }

    /** 是否已经给过本 App 使用 Shizuku 的权限。 */
    static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 弹出 Shizuku 授权框（结果通过 Shizuku.addRequestPermissionResultListener 回调）。 */
    static boolean requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 以 shell 身份执行 pm grant，成功后返回 true。 */
    static boolean grantSecureSettings(Context context) {
        String command = "pm grant " + context.getPackageName()
            + " android.permission.WRITE_SECURE_SETTINGS";
        return runShell(command, 8000);
    }

    /** 以 shell 身份执行一条命令（newProcess 在不同版本里可见性不同，用反射调用）。 */
    static boolean runShell(String command, long timeoutMs) {
        Process process = null;
        try {
            java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            process = (Process) method.invoke(null, new String[] {"sh", "-c", command}, null, null);
            final Process target = process;
            final boolean[] finished = {false};
            Thread waiter = new Thread(() -> {
                try {
                    target.waitFor();
                } catch (Throwable ignored) {
                } finally {
                    finished[0] = true;
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline && !finished[0]) {
                Thread.sleep(80);
            }
            return finished[0] && process.exitValue() == 0;
        } catch (Throwable error) {
            return false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
