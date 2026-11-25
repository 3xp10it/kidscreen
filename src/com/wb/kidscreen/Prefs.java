package com.wb.kidscreen;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** 所有配置和运行状态都放在一个 SharedPreferences 里，服务和界面共用。 */
public class Prefs {

    private static final String NAME = "kidscreen";

    public static final String KEY_TARGETS = "targets";
    public static final String KEY_LIMIT_MIN = "limit_min";
    public static final String KEY_USED_MS = "used_ms";
    public static final String KEY_BLOCKED_UNTIL = "blocked_until";
    public static final String KEY_LAST_ACTIVE = "last_active";
    public static final String KEY_TRIGGER_COUNT = "trigger_count";
    public static final String KEY_LAST_TRIGGER = "last_trigger";
    public static final String KEY_MUTED = "muted";
    public static final String KEY_SAVED_VOLUME = "saved_volume";
    public static final String KEY_PROMPT = "prompt";
    public static final String KEY_LOCK_TIP = "lock_tip_done";

    public static final String DEFAULT_PROMPT = "宝宝，要休息了";

    public static final int DEFAULT_LIMIT_MIN = 30;

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> targets(Context c) {
        Set<String> s = get(c).getStringSet(KEY_TARGETS, null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    public static void setTargets(Context c, Set<String> v) {
        get(c).edit().putStringSet(KEY_TARGETS, new HashSet<String>(v)).apply();
    }

    public static int limitMin(Context c) {
        return get(c).getInt(KEY_LIMIT_MIN, DEFAULT_LIMIT_MIN);
    }

    public static long limitMs(Context c) {
        return limitMin(c) * 60L * 1000L;
    }

    /** 冷却时长：到点之后这段时间内，重新打开目标 app 会被立刻踢回。 */
    public static long cooldownMs(Context c) {
        return limitMs(c);
    }

    public static long usedMs(Context c) {
        return get(c).getLong(KEY_USED_MS, 0L);
    }

    public static void setUsedMs(Context c, long v) {
        get(c).edit().putLong(KEY_USED_MS, Math.max(0L, v)).apply();
    }

    public static long blockedUntil(Context c) {
        return get(c).getLong(KEY_BLOCKED_UNTIL, 0L);
    }

    public static void setBlockedUntil(Context c, long v) {
        get(c).edit().putLong(KEY_BLOCKED_UNTIL, v).apply();
    }

    public static long lastActive(Context c) {
        return get(c).getLong(KEY_LAST_ACTIVE, 0L);
    }

    public static void setLastActive(Context c, long v) {
        get(c).edit().putLong(KEY_LAST_ACTIVE, v).apply();
    }

    public static int triggerCount(Context c) {
        return get(c).getInt(KEY_TRIGGER_COUNT, 0);
    }

    public static void bumpTrigger(Context c) {
        get(c).edit()
                .putInt(KEY_TRIGGER_COUNT, triggerCount(c) + 1)
                .putLong(KEY_LAST_TRIGGER, System.currentTimeMillis())
                .apply();
    }

    public static String lastTriggerText(Context c) {
        long t = get(c).getLong(KEY_LAST_TRIGGER, 0L);
        if (t <= 0) return "还没有触发过";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
        return f.format(new java.util.Date(t));
    }

    /** 是否被我们静音过（用于恢复） */
    public static boolean isMuted(Context c) {
        return get(c).getBoolean(KEY_MUTED, false);
    }

    public static void setMuted(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_MUTED, v).apply();
    }

    public static int savedVolume(Context c) {
        return get(c).getInt(KEY_SAVED_VOLUME, -1);
    }

    public static void setSavedVolume(Context c, int v) {
        get(c).edit().putInt(KEY_SAVED_VOLUME, v).apply();
    }

    /** 到点播放的语音内容 */
    public static String prompt(Context c) {
        String s = get(c).getString(KEY_PROMPT, null);
        if (s == null || s.trim().isEmpty()) return DEFAULT_PROMPT;
        return s.trim();
    }

    public static void setPrompt(Context c, String v) {
        get(c).edit().putString(KEY_PROMPT, v == null ? "" : v.trim()).apply();
    }

    /**
     * 用户是否已确认「最近任务卡片已锁定」。
     * 系统没有 API 能查到卡片锁没锁，只能让用户自己确认一次，之后不再唠叨。
     */
    public static boolean lockTipDone(Context c) {
        return get(c).getBoolean(KEY_LOCK_TIP, false);
    }

    public static void setLockTipDone(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_LOCK_TIP, v).apply();
    }
}
