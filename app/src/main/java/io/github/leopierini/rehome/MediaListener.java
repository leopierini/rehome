package io.github.leopierini.rehome;

import android.service.notification.NotificationListenerService;

/**
 * category_player: a notification listener that reads no notification. It is there to be named to
 * MediaSessionManager, which lists other apps' media sessions only to an enabled listener, and because Android keeps
 * it bound while it is enabled: the player's memory (MediaMemory) records what other apps play from here, also while
 * another app is in front. It overrides only connect and disconnect: it does not read, keep or log notifications.
 * Enabled once over ADB by tools/rehome.py ({@code cmd notification allow_listener
 * io.github.leopierini.rehome/.MediaListener}); without it the player shows only Watch Next rows. Kept apart from GuardRevival on
 * purpose: enabling that one also turns on the Home key guard.
 */
public final class MediaListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        if (!getResources().getBoolean(R.bool.category_player)) return;
        // One recorder per process (MediaMemory.get): a disconnect and connect in quick succession, by this instance
        // or a new one, are handled in order on its own thread.
        MediaMemory.get(this).start(this);
    }

    @Override
    public void onListenerDisconnected() {
        if (!getResources().getBoolean(R.bool.category_player)) return;
        MediaMemory.get(this).stop(this);
    }
}
