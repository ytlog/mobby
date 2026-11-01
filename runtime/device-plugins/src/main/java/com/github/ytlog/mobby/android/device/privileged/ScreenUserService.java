package com.github.ytlog.mobby.android.device.privileged;

import android.os.Binder;
import android.os.Parcel;

/** Shizuku loads this Binder in its shell/root UserService process. */
public final class ScreenUserService extends Binder {
    private ScreenServer server;
    public ScreenUserService() { }

    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == 1) {
            try {
                if (server != null) server.close();
                server = new ScreenServer();
                reply.writeNoException(); reply.writeString(server.endpoint());
            } catch (Exception failure) { reply.writeException(new IllegalStateException("virtual_screen_connect_failed")); }
            return true;
        }
        if (code == 16777115) {
            if (server != null) server.close();
            System.exit(0);
            return true;
        }
        return false;
    }
}
