package com.wb.kidscreen;

import android.service.notification.NotificationListenerService;

/**
 * 这个服务本身不做任何事。
 *
 * 存在的唯一目的：拿到"通知使用权"后，才能通过 MediaSessionManager
 * 取得 MediaController，进而暂停别的 app 的播放。
 * 否则第三方 app 无权控制别人的媒体会话。
 */
public class NotifListener extends NotificationListenerService {
}
