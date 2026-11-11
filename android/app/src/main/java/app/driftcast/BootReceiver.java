package app.driftcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** TVs come back ready to receive after a restart or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Prefs.isTv(c)) ReceiverService.start(c);
    }
}
