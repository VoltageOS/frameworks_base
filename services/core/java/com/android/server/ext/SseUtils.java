package com.android.server.ext;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public class SseUtils {

    public static Notification.Action notifAction(Intent broadcastIntent, int textRes) {
        return notifActionBuilder(broadcastIntent, textRes).build();
    }

    public static Notification.Action.Builder notifActionBuilder(Intent broadcastIntent, int textRes) {
        var ctx = SystemServerExt.get().context;
        var pi = PendingIntent.getBroadcast(ctx, 0, broadcastIntent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, ctx.getText(textRes), pi);
    }

    public static void addNotifAction(Context ctx, PendingIntent intent, int textRes, Notification.Builder dst) {
        var nb = new Notification.Action.Builder(null, ctx.getText(textRes), intent);
        dst.addAction(nb.build());
    }
}
