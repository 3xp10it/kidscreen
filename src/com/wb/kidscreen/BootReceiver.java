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
 * 开机自检：Android 13 起，侧载/重装的 app 无障碍授权可能被系统回收，
 * 表现就是「计时服务：未开启」而用户完全不知道。开机后主动查一次，
 * 没跑就发条通知把人叫回来，而不是等孩子看完一整集才发现闸门没生效。
 *
 * 注意别在 onReceive 里 sleep 等系统绑服务：goAsync 的宽限期只有十几秒，
 * 进程会被提前杀掉，通知根本发不出去（实测就是这样丢的）。
 * 改成用 AlarmManager 延后 30 秒再查。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_CHECK = "com.wb.kidscreen.CHECK_ACCESSIBILITY";
    private static final String CH = "boot_check";
    private static final String CH_NAME = "开机自检";
    private static final long DELAY_MS = 30000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            scheduleCheck(context.getApplicationContext());
        } else if (ACTION_CHECK.equals(action)) {
            check(context.getApplicationContext());
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
                    SystemClock.elapsedRealtime() + DELAY_MS, pi);
            android.util.Log.i("kidscreen", "boot check scheduled");
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "schedule boot check failed", t);
        }
    }

    private void check(Context c) {
        try {
            int st = SvcState.get(c);
            android.util.Log.i("kidscreen", "boot check state=" + st);
            if (st != SvcState.ON) {
                postNotice(c);
            }
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "boot check failed", t);
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
                    .setContentText("无障碍服务被系统关了，点这里恢复")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "无障碍服务被系统关了，看动画时长不会生效。\n"
                            + "点这条通知打开 app，按提示重新开启；\n"
                            + "如果开了还是不行，去「应用详情 → ⋮ → 允许受限设置」。"))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(1001, b.build());
            android.util.Log.i("kidscreen", "boot notice posted");
        } catch (Throwable t) {
            android.util.Log.e("kidscreen", "boot notice failed", t);
        }
    }
}
