package com.wb.kidscreen;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 无障碍服务的三种真实状态。
 *
 * 只查 Settings.Secure 是不够的：Android 13 起，侧载/重装过的 app 即使被勾选了，
 * 系统也会因为「受限设置」没允许而拒绝绑定服务 —— 设置里看着是开的，实际没跑。
 * 所以要再问一次 AccessibilityManager「现在有哪些服务真的在运行」。
 */
public final class SvcState {
    public static final int OFF = 0;      // 无障碍列表里没勾
    public static final int BLOCKED = 1;  // 勾了，但系统没让它跑（多半是受限设置）
    public static final int ON = 2;       // 正常运行

    private SvcState() {
    }

    public static int get(Context c) {
        String me = c.getPackageName() + "/" + WatchService.class.getName();
        boolean listed = false;
        try {
            String en = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (en != null) {
                for (String s : en.split(":")) {
                    if (s.equalsIgnoreCase(me)) {
                        listed = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!listed) {
            return OFF;
        }
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(
                    Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> l = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo i : l) {
                if (i.getResolveInfo() == null || i.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                if (c.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)
                        && WatchService.class.getName().equals(i.getResolveInfo().serviceInfo.name)) {
                    return ON;
                }
            }
        } catch (Throwable ignored) {
        }
        return BLOCKED;
    }

    public static boolean on(Context c) {
        return get(c) == ON;
    }
}
