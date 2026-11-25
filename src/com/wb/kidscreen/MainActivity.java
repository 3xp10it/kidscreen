package com.wb.kidscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView statusView;
    private LinearLayout fixPanel;      // 无障碍没在运行时的修复卡片
    private TextView fixText;
    private TextView targetsView;
    private EditText minutesView;
    private EditText promptView;
    private LinearLayout root;
    private TextToSpeech previewTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addTitle("动画管家");
        addHint("看动画的 app 连续用满设定时长，就自动退回桌面并锁屏。");

        statusView = addText("");
        statusView.setTextSize(14);

        // 无障碍没跑起来时的修复卡片：把「为什么」和「点哪里」写清楚。
        // 只写「点第①步」没用 —— Android 13 起侧载/重装的 app 会先被「受限设置」拦住，
        // 用户在无障碍里勾了也起不来，得先去应用详情里允许受限设置。
        fixPanel = new LinearLayout(this);
        fixPanel.setOrientation(LinearLayout.VERTICAL);
        fixPanel.setBackgroundColor(0x1AFF5722);
        int fp = dp(12);
        fixPanel.setPadding(fp, fp, fp, fp);
        fixPanel.setVisibility(View.GONE);
        fixText = new TextView(this);
        fixText.setTextSize(13);
        fixPanel.addView(fixText);
        fixPanel.addView(makeButton("允许受限设置（进应用详情）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetails();
            }
        }));
        fixPanel.addView(makeButton("打开无障碍设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("请手动进入：设置 → 无障碍");
                }
            }
        }));
        root.addView(fixPanel);

        addSpace();

        addButton("① 打开无障碍设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("请在列表里找到「动画管家」并打开");
                } catch (Throwable t) {
                    toast("打不开设置，请手动进入：设置 → 无障碍");
                }
            }
        });

        addButton("② 选择要限制的 app", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApps();
            }
        });

        targetsView = addText("");
        targetsView.setTextSize(13);

        addSpace();

        addText("每次允许观看（分钟）").setTextSize(13);
        minutesView = new EditText(this);
        minutesView.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesView.setText(String.valueOf(Prefs.limitMin(this)));
        root.addView(minutesView);

        addButton("保存时长", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveMinutes();
            }
        });

        addSpace();

        addText("到点播放的语音").setTextSize(13);
        promptView = new EditText(this);
        promptView.setInputType(InputType.TYPE_CLASS_TEXT);
        promptView.setText(Prefs.prompt(this));
        root.addView(promptView);

        addButton("试听并保存语音", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = promptView.getText().toString().trim();
                if (t.isEmpty()) {
                    toast("请输入要播报的内容");
                    return;
                }
                Prefs.setPrompt(MainActivity.this, t);
                previewVoice(t);
            }
        });

        addSpace();

        addButton("③ 关闭电池优化（防止后台被杀）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBattery();
            }
        });

        addSpace();

        addButton("④ 授权通知使用权（用于停止声音）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    toast("在列表里找到「动画管家 · 暂停播放」并打开");
                } catch (Throwable t) {
                    toast("请手动进入：设置 → 通知与状态栏 → 通知使用权");
                }
            }
        });

        addSpace();

        addButton("清零计时 / 解除冷却", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setUsedMs(MainActivity.this, 0L);
                Prefs.setBlockedUntil(MainActivity.this, 0L);
                toast("已清零");
                refresh();
            }
        });

        addButton("恢复音量（若被静音）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am == null) return;
                    int vol = Prefs.savedVolume(MainActivity.this);
                    if (vol < 0) vol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2;
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                    Prefs.setMuted(MainActivity.this, false);
                    toast("音量已恢复到 " + vol);
                } catch (Throwable t) {
                    toast("恢复失败，请用音量键手动调");
                }
            }
        });

        addSpace();
        addHint("提示：ColorOS 可能限制后台。若到点没生效，请到 设置 → 应用管理 → 动画管家 → 耗电管理，允许后台活动。");

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------- 界面小工具 ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView addTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setPadding(0, 0, 0, dp(8));
        root.addView(t);
        return t;
    }

    private TextView addText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(6), 0, dp(6));
        root.addView(t);
        return t;
    }

    private TextView addHint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(4), 0, dp(8));
        root.addView(t);
        return t;
    }

    private void addSpace() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        root.addView(v);
    }

    private Button makeButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button addButton(String s, View.OnClickListener l) {
        Button b = makeButton(s, l);
        root.addView(b);
        return b;
    }

    /** 跳到本应用的系统详情页：右上角 ⋮ 里就是「允许受限设置」。 */
    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("点右上角 ⋮ → 允许受限设置");
        } catch (Throwable t) {
            toast("请手动进入：设置 → 应用 → 动画管家");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 状态刷新 ----------------

    private void refresh() {
        int st = SvcState.get(this);
        long used = Prefs.usedMs(this);
        long limit = Prefs.limitMs(this);
        long blocked = Prefs.blockedUntil(this);
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("计时服务：")
                .append(st == SvcState.ON ? "已开启 ✓"
                        : st == SvcState.BLOCKED ? "勾了但没运行 ✗（看下面的提示）"
                        : "未开启 ✗（看下面的提示）")
                .append("\n");
        boolean notif = false;
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            notif = enabled != null && enabled.contains(getPackageName());
        } catch (Throwable ignored) {
        }
        sb.append("停止声音：").append(notif ? "已授权 ✓" : "未授权 ✗（点第④步）").append("\n");
        sb.append("上限：").append(limit / 60000).append(" 分钟\n");
        sb.append("已用：").append(fmt(used));
        if (blocked > now) {
            sb.append("\n冷却中：还剩 ").append((blocked - now) / 1000).append(" 秒");
        }
        sb.append("\n触发次数：").append(Prefs.triggerCount(this));
        sb.append("\n最近一次：").append(Prefs.lastTriggerText(this));
        statusView.setText(sb.toString());

        if (st == SvcState.ON) {
            fixPanel.setVisibility(View.GONE);
        } else {
            fixPanel.setVisibility(View.VISIBLE);
            fixText.setText(st == SvcState.BLOCKED
                    ? "无障碍里已经勾了「动画管家 · 使用计时」，但系统没让它运行。\n"
                      + "Android 13 起，侧载/重装过的 app 要先允许「受限设置」：\n"
                      + "点下面的「允许受限设置」→ 右上角 ⋮ → 允许受限设置 → 再回无障碍打开它。\n"
                      + "（每次重装 app 都要重新允许一次）"
                    : "无障碍服务还没开：点下面的「① 打开无障碍设置」，"
                      + "找到「动画管家 · 使用计时」打开。\n"
                      + "如果列表里已经开了、这里还是这个状态，就是被「受限设置」拦住了，"
                      + "先点「允许受限设置」那个按钮。");
        }

        Set<String> t = Prefs.targets(this);
        if (t.isEmpty()) {
            targetsView.setText("尚未选择 app");
        } else {
            PackageManager pm = getPackageManager();
            StringBuilder names = new StringBuilder("已选 " + t.size() + " 个：");
            for (String p : t) {
                names.append("\n· ").append(labelOf(pm, p));
            }
            targetsView.setText(names.toString());
        }
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void saveMinutes() {
        int v;
        try {
            v = Integer.parseInt(minutesView.getText().toString().trim());
        } catch (Throwable t) {
            toast("请输入数字");
            return;
        }
        if (v < 1 || v > 600) {
            toast("请输入 1 ~ 600");
            return;
        }
        Prefs.get(this).edit().putInt(Prefs.KEY_LIMIT_MIN, v).apply();
        toast("已保存：" + v + " 分钟");
        refresh();
    }

    private void requestIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已经是免优化状态了");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请手动到 电池设置 里允许后台运行");
            }
        }
    }

    private void previewVoice(final String text) {
        try {
            if (previewTts != null) {
                previewTts.stop();
                previewTts.shutdown();
                previewTts = null;
            }
            previewTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS && previewTts != null) {
                        previewTts.setLanguage(Locale.CHINA);
                        try {
                            previewTts.setSpeechRate(0.92f);
                        } catch (Throwable ignored) {
                        }
                        previewTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview");
                    } else {
                        toast("语音引擎不可用，试听失败");
                    }
                }
            });
        } catch (Throwable t) {
            toast("试听失败");
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (previewTts != null) {
                previewTts.stop();
                previewTts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------- 选择 app ----------------

    private void pickApps() {
        final PackageManager pm = getPackageManager();
        final Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<ResolveInfo> ris;
                try {
                    ris = pm.queryIntentActivities(main, 0);
                } catch (Throwable t) {
                    ris = new ArrayList<ResolveInfo>();
                }
                final List<String> pkgs = new ArrayList<String>();
                final List<String> labels = new ArrayList<String>();
                Set<String> seen = new HashSet<String>();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    if (p == null || p.equals(getPackageName()) || seen.contains(p)) continue;
                    seen.add(p);
                    pkgs.add(p);
                    labels.add(String.valueOf(ri.loadLabel(pm)));
                }
                final Collator c = Collator.getInstance(Locale.CHINA);
                Integer[] idx = new Integer[pkgs.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                final List<Integer> order = new ArrayList<Integer>();
                Collections.addAll(order, idx);
                Collections.sort(order, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return c.compare(labels.get(a), labels.get(b));
                    }
                });

                final List<String> sPkg = new ArrayList<String>();
                final List<String> sLabel = new ArrayList<String>();
                for (Integer i : order) {
                    sPkg.add(pkgs.get(i));
                    sLabel.add(labels.get(i));
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showPicker(sPkg, sLabel);
                    }
                });
            }
        }).start();
    }

    private void showPicker(final List<String> pkgs, List<String> labels) {
        if (pkgs.isEmpty()) {
            toast("没有读到应用列表");
            return;
        }
        final Set<String> current = Prefs.targets(this);
        final String[] items = labels.toArray(new String[0]);
        final boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            checked[i] = current.contains(pkgs.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要限制的 app")
                .setMultiChoiceItems(items, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Set<String> sel = new HashSet<String>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) sel.add(pkgs.get(i));
                        }
                        Prefs.setTargets(MainActivity.this, sel);
                        Prefs.setUsedMs(MainActivity.this, 0L);
                        Prefs.setBlockedUntil(MainActivity.this, 0L);
                        toast("已保存 " + sel.size() + " 个 app");
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
