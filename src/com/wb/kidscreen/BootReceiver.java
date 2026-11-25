package com.wb.kidscreen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 无障碍服务的自检与看门狗。
 *
 * 为什么需要：Android 13 起侧载 app 的无障碍授权可能被系统回收；更常见的是
 * ColorOS 这类 ROM，用户从「最近任务」里把动画管家划掉（或点「清除」）时，
 * 系统会把 enabled_accessibility_services 直接清空 —— 计时就此静默失效，
 * 界面上要等下次打开 app 才发现。
 *
 * 所以做两件事：
 *   1. 开机 30 秒后查一次（等系统把服务绑起来，避免误报）
 *   2. 每 10 分钟查一次，服务没在跑就发通知；恢复后自动把通知撤掉
 *
 * 注意别在 onReceive 里 sleep 等系统绑服务：goAsync 的宽限期只有十几秒，
 * 进程会被提前杀掉，通知根本发不出去（实测就是这样丢的）。用 AlarmManager 延后。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_CHECK = "com.wb.kidscreen.CHECK_ACCESSIBILITY";
    private static final String ACTION_WATCH = "com.wb.kidscreen.WATCHDOG";
    private static final String CH = "boot_check";
    private static final String CH_NAME = "服务自检";
    private static final int NOTICE_ID = 1001;
    private static final long BOOT_DELAY_MS = 30000L;
    private static final long WATCH_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Context c = context.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            scheduleCheck(c);
            ensureWatchdog(c);
        } else if (ACTION_CHECK.equals(action) || ACTION_WATCH.equals(action)) {
            check(c);
        }
    }

    /** 由 MainActivity 调用：装完/打开 app 就把看门狗挂上，不用等下次开机。 */
    public static void ensureWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_WATCH);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + WATCH_MS, WATCH_MS, pi);
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "schedule watchdog failed", t);
        }
    }

    private void scheduleCheck(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_CHECK);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + BOOT_DELAY_MS, pi);
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "schedule boot check failed", t);
        }
    }

    private void check(Context c) {
        try {
            int st = SvcState.get(c);
            android.util.Log.i("kidscreen", "check state=" + st);
            if (st == SvcState.ON) {
                cancelNotice(c);   // 恢复了就把告警撤掉
            } else {
                postNotice(c);
            }
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "check failed", t);
        }
    }

    private void cancelNotice(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTICE_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private void postNotice(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CH, CH_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);

            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = new Notification.Builder(c, CH)
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentTitle("动画管家没在计时")
                    .setContentText("无障碍服务被系统关掉了，点这里恢复")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "无障碍服务被系统关掉了，看动画时长不会生效。\n"
                            + "常见原因：从「最近任务」里把动画管家划掉/清除了，或者重装过 app。\n"
                            + "点这条通知打开 app，按提示重新开启即可。"))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(NOTICE_ID, b.build());
            android.util.Log.i("kidscreen", "notice posted");
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "notice failed", t);
        }
    }
}
