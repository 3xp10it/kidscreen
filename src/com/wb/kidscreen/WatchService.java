package com.wb.kidscreen;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 无障碍服务：盯着前台是哪个 app，目标 app 用够时长就把它顶回桌面并锁屏。
 *
 * 关键设计：不依赖"窗口变化事件"来判断前台是谁。
 * 因为对话框、通知栏、锁屏这类临时窗口出现时会打断状态，而它们消失时
 * 系统不会再发一次事件，状态就卡死了。改成每秒主动查一次前台包名，
 * 任何临时窗口消失后都能自动恢复。
 */
public class WatchService extends AccessibilityService {

    private static final String TAG = "Kidscreen";
    private static final String CH_ID = "kidscreen";

    /** 离开目标 app 超过这个时间，就认为这一轮结束，计时清零 */
    private static final long RESET_GAP_MS = 30 * 60 * 1000L;
    /** 冷却期内反复打开时，两次踢回的最小间隔 */
    private static final long KICK_DEBOUNCE_MS = 2500L;

    /**
     * 家长在界面上改了限额，要求把这一轮计时和冷却都清零。
     * 界面和服务在同一个进程里，用静态标志传递最省事 —— 不需要广播，也不用重启服务。
     * 不清零的话会很难理解：限额从 30 分钟改成 2 分钟，已用的 1 分 15 秒还在，
     * 孩子看 45 秒就被限制了，看起来像「压根没过 2 分钟」。
     */
    private static volatile boolean pendingReset = false;

    public static void requestReset() {
        pendingReset = true;
    }

    private Handler handler;
    private Runnable ticker;

    private Set<String> targets;
    private long limitMs;
    private long usedMs;
    private boolean targetForeground;
    private long lastForegroundTs;
    private long lastKickTs;
    private int saveCounter;

    private TextToSpeech tts;
    private boolean ttsReady;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");

        handler = new Handler(Looper.getMainLooper());
        targets = Prefs.targets(this);
        limitMs = Prefs.limitMs(this);
        usedMs = Prefs.usedMs(this);
        targetForeground = false;
        lastForegroundTs = System.currentTimeMillis();

        // 重启/长时间闲置后，把上一轮的计时清掉。
        // 否则会出现"昨天看了 25 分钟，今天开机只剩 5 分钟"这种怪事。
        long lastActive = Prefs.lastActive(this);
        if (usedMs > 0 && lastActive > 0
                && System.currentTimeMillis() - lastActive > RESET_GAP_MS) {
            usedMs = 0;
            Prefs.setUsedMs(this, 0L);
            Log.i(TAG, "stale session cleared (idle " +
                    ((System.currentTimeMillis() - lastActive) / 60000) + " min)");
        }

        createChannel();
        startTicker();
        initTts();

        // 上次被静音了但冷却早就过了（比如中途重启过）→ 把音量还回去
        if (Prefs.isMuted(this) && Prefs.blockedUntil(this) <= System.currentTimeMillis()) {
            restoreMedia();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // 事件只用来"尽快"感知切换；真正的判断在 tick 里轮询完成
        try {
            long now = System.currentTimeMillis();
            targets = Prefs.targets(this);
            limitMs = Prefs.limitMs(this);
            updateForegroundState(now);
            if (targetForeground && Prefs.blockedUntil(this) > now) {
                kick();
            }
        } catch (Throwable t) {
            Log.w(TAG, "event handling failed", t);
        }
    }

    private void startTicker() {
        ticker = new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    Log.e(TAG, "tick error", t);
                }
                handler.postDelayed(this, 1000L);
            }
        };
        handler.postDelayed(ticker, 1000L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        limitMs = Prefs.limitMs(this);
        targets = Prefs.targets(this);

        // 家长改过限额：这一轮从头开始，冷却也一起解除
        if (pendingReset) {
            pendingReset = false;
            usedMs = 0;
            Prefs.setUsedMs(this, 0L);
            Prefs.setBlockedUntil(this, 0L);
            restoreMedia();
            Log.i(TAG, "reset by settings change");
        }

        // 冷却到期自动解除，并把音量还回去
        long blocked = Prefs.blockedUntil(this);
        if (blocked > 0 && now >= blocked) {
            Prefs.setBlockedUntil(this, 0L);
            restoreMedia();
        }

        updateForegroundState(now);

        if (!targetForeground) {
            // 长时间没碰目标 app → 这一轮作废，重新计时
            if (usedMs > 0 && now - lastForegroundTs > RESET_GAP_MS) {
                usedMs = 0;
                Prefs.setUsedMs(this, 0L);
                Log.i(TAG, "session reset (idle gap)");
            }
            return;
        }

        // 冷却期内又打开了：直接踢回去
        if (Prefs.blockedUntil(this) > now) {
            kick();
            return;
        }

        // 熄屏时不计时
        if (!isScreenOn()) return;

        usedMs += 1000L;
        if (usedMs >= limitMs) {
            trigger();
            return;
        }
        if (++saveCounter >= 10) {
            saveCounter = 0;
            Prefs.setUsedMs(this, usedMs);
            Prefs.setLastActive(this, now);
        }
    }

    /** 轮询当前前台包名，更新 targetForeground */
    private void updateForegroundState(long now) {
        String fg = currentForegroundPkg();
        if (fg == null) return;                 // 查不到就维持原状，不轻易翻转
        boolean isTarget = targets != null && targets.contains(fg);

        if (isTarget) {
            if (!targetForeground) {
                targetForeground = true;
                lastForegroundTs = now;
                Prefs.setLastActive(this, now);
                Log.i(TAG, "target entered: " + fg + " used=" + usedMs);
            }
        } else if (targetForeground) {
            targetForeground = false;
            lastForegroundTs = now;
            Prefs.setUsedMs(this, usedMs);
            Log.i(TAG, "target left (" + fg + "), used=" + usedMs);
        }
    }

    private String currentForegroundPkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence p = root.getPackageName();
            return p == null ? null : p.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 初始化语音合成（用系统默认引擎，这台平板上是讯飞） */
    private void initTts() {
        try {
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        Log.w(TAG, "tts init failed: " + status);
                        return;
                    }
                    int r = tts.setLanguage(Locale.CHINA);
                    ttsReady = (r != TextToSpeech.LANG_MISSING_DATA
                            && r != TextToSpeech.LANG_NOT_SUPPORTED);
                    try {
                        tts.setSpeechRate(0.92f);   // 稍慢一点，听得清
                    } catch (Throwable ignored) {
                    }
                    Prefs.get(WatchService.this).edit()
                            .putBoolean("tts_ready", ttsReady).apply();
                    Log.i(TAG, "tts ready=" + ttsReady + " langResult=" + r);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "tts create failed", t);
        }
    }

    /**
     * 播报语音，说完（或超时）再执行 after。
     * 引擎不可用时直接执行 after，不影响锁屏。
     */
    private void speakPrompt(final Runnable after) {
        final boolean[] finished = {false};
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                finished[0] = true;
                if (after != null) after.run();
            }
        };
        try {
            if (tts != null && ttsReady) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        handler.post(done);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        handler.post(done);
                    }
                });
                int r = tts.speak(Prefs.prompt(this), TextToSpeech.QUEUE_FLUSH, null, "rest");
                Prefs.get(this).edit()
                        .putInt("tts_result", r)
                        .putLong("tts_at", System.currentTimeMillis())
                        .apply();
                if (r == TextToSpeech.SUCCESS) {
                    handler.postDelayed(done, 6000L);   // 兜底：回调不来也照常锁屏
                    return;
                }
                Log.w(TAG, "tts.speak returned " + r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "speak failed", t);
        }
        done.run();
    }

    /** 时间到：停播放 → 退桌面 → 语音提示 → 锁屏，并进入冷却期 */
    private void trigger() {
        long now = System.currentTimeMillis();
        Log.i(TAG, "TRIGGER at used=" + usedMs);

        targetForeground = false;
        lastForegroundTs = now;
        usedMs = 0;

        Prefs.setUsedMs(this, 0L);
        Prefs.setBlockedUntil(this, now + Prefs.cooldownMs(this));
        Prefs.bumpTrigger(this);

        notifyUser("时间到啦，已经帮你关掉了。休息一下眼睛吧～");

        // 顺序很重要：
        // 1) 先停播放，避免"听得到声音、看不到画面"让孩子更闹
        pauseTargets();
        killTargets();
        // 2) 立刻退回桌面，让画面消失
        performGlobalAction(GLOBAL_ACTION_HOME);
        // 3) 说一句提示，说完再锁屏（语音不会被锁屏打断）
        speakPrompt(new Runnable() {
            @Override
            public void run() {
                muteMedia();
                lockScreen();
            }
        });
    }

    /** 冷却期内又打开了目标 app：立刻再踢一次 */
    private void kick() {
        long now = System.currentTimeMillis();
        if (now - lastKickTs < KICK_DEBOUNCE_MS) return;
        lastKickTs = now;
        Log.i(TAG, "kick during cooldown");

        long left = (Prefs.blockedUntil(this) - now) / 1000L;
        if (left > 0) {
            toast("还在休息，剩 " + (left / 60) + " 分 " + (left % 60) + " 秒");
        } else {
            toast("还没到时间哦～");
        }
        pauseTargets();
        muteMedia();
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                lockScreen();
            }
        }, 600L);
    }

    /** 尽力结束目标 app 的进程，让它彻底停止播放 */
    private void killTargets() {
        if (targets == null) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            for (String p : targets) {
                try {
                    am.killBackgroundProcesses(p);
                    Log.i(TAG, "killed background: " + p);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "killTargets failed", t);
        }
    }

    /** 暂停目标 app 的媒体播放（需要"通知使用权"，否则拿不到 MediaController） */
    private void pauseTargets() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> list =
                    msm.getActiveSessions(new ComponentName(this, NotifListener.class));
            if (list == null) return;
            for (MediaController c : list) {
                try {
                    String pkg = c.getPackageName();
                    if (targets != null && targets.contains(pkg)) {
                        c.getTransportControls().pause();
                        Log.i(TAG, "paused media: " + pkg);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pauseTargets failed (通知使用权可能未授权)", t);
        }
    }

    /** 把媒体音量压到 0（记住原值，冷却结束后恢复） */
    private void muteMedia() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            if (!Prefs.isMuted(this)) {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                Prefs.setSavedVolume(this, cur);
                Prefs.setMuted(this, true);
                Log.i(TAG, "saved volume=" + cur);
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            Log.i(TAG, "muted music stream");
        } catch (Throwable t) {
            Log.w(TAG, "mute failed", t);
        }
    }

    /** 恢复被我们改过的音量 */
    private void restoreMedia() {
        try {
            if (!Prefs.isMuted(this)) return;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int v = Prefs.savedVolume(this);
            if (v < 0) v = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Prefs.setMuted(this, false);
            Log.i(TAG, "restored volume=" + v);
        } catch (Throwable t) {
            Log.w(TAG, "restore failed", t);
        }
    }

    private void lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            } catch (Throwable t) {
                Log.w(TAG, "lock screen failed", t);
            }
        }
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "使用提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("到点关闭动画 app 时提醒");
            nm.createNotificationChannel(ch);
        }
    }

    private void notifyUser(String text) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CH_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentTitle("动画管家")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        // 无障碍被系统中断时无需特殊处理
    }

    @Override
    public void onDestroy() {
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
        }
        if (targetForeground) {
            Prefs.setUsedMs(this, usedMs);
        }
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
