package com.selinuxbridge.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Hands Termux a USB device this app holds the permission for.
 *
 * Android grants USB access per app, and the lasting kind -- re-granted on
 * every plug-in with no question -- only to an app with an activity that
 * handles the device (UsbAttachActivity). Termux has none, so its grant
 * dies with each unplug. This app can hold the lasting one; what Termux
 * needs from it is then just the open descriptor, which a Binder can carry.
 *
 * A Termux process has no Context and so cannot bind to a service, but it
 * can send a broadcast. The exchange is:
 *
 *   1. Termux sends ACTION to this receiver, with a Binder of its own in
 *      EXTRA_LINK under "callback".
 *   2. This receiver answers on that Binder (CALLBACK_LINK, one-way) with a
 *      Link -- a Binder served by this app.
 *   3. Termux calls the Link: LIST to see which devices this app may open,
 *      OPEN to get one opened, asking Android first if need be.
 *
 * The receiver has to be exported for Termux to reach it, so anyone can get
 * a Link. That is why the Link checks every call against the caller's uid,
 * which the kernel reports and nobody can forge: only Termux (and the apps
 * that share its uid) is served.
 *
 * Every reply starts with a status and a message for people, so a refusal
 * reads as a reason on the Debian side rather than as a dead socket.
 */
public class UsbLinkReceiver extends BroadcastReceiver {
    private static final String TAG = "SELinuxBridge";

    static final String ACTION = "com.selinuxbridge.app.action.USB_LINK";
    static final String EXTRA_LINK = "link";
    static final String DESCRIPTOR = "com.selinuxbridge.app.UsbLink";
    static final int VERSION = 1;

    /** Sent to the caller's Binder: DESCRIPTOR, VERSION, the Link. */
    static final int CALLBACK_LINK = 1;

    /** Reply: n, then n x (device name, 1 if this app may open it). */
    static final int LIST = 1;
    /** Args: device name, seconds to wait for an answer if Android must ask
     *  (0: do not ask). Reply on OK: the open descriptor. */
    static final int OPEN = 2;

    static final int OK = 0;
    static final int NOT_ATTACHED = 1;
    static final int NOT_ALLOWED = 2;
    static final int TIMED_OUT = 3;
    static final int FAILED = 4;
    static final int REFUSED = 5;

    /** The only uid served: Termux's, which its plugins share. */
    static final String TERMUX = "com.termux";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extra = intent != null ? intent.getBundleExtra(EXTRA_LINK) : null;
        IBinder callback = extra != null ? extra.getBinder("callback") : null;
        if (callback == null) {
            Log.w(TAG, "UsbLink: a request without a callback, ignored");
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeString(DESCRIPTOR);
            data.writeInt(VERSION);
            data.writeStrongBinder(new Link(context.getApplicationContext()));
            callback.transact(CALLBACK_LINK, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "UsbLink: the caller went away before it could be answered", e);
        } finally {
            data.recycle();
        }
    }

    static final class Link extends Binder {
        /** One permission question at a time: Android shows one anyway. */
        private static final Object ASKING = new Object();

        private final Context app;

        Link(Context app) {
            this.app = app;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != LIST && code != OPEN) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            if (reply == null) {
                return true;
            }
            int uid = Binder.getCallingUid();
            if (!isTermux(uid)) {
                Log.w(TAG, "UsbLink: refused uid " + uid);
                status(reply, REFUSED, "only Termux may use this; uid " + uid + " is not Termux");
                return true;
            }
            UsbManager usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
            if (usb == null) {
                status(reply, FAILED, "this phone has no USB host support");
                return true;
            }
            if (code == LIST) {
                list(usb, reply);
            } else {
                open(usb, data.readString(), data.readInt(), reply);
            }
            return true;
        }

        // Termux must be named in the manifest's <queries>: without that,
        // package-visibility filtering hides it and nothing would match.
        private boolean isTermux(int uid) {
            String[] packages = app.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String p : packages) {
                    if (TERMUX.equals(p)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void list(UsbManager usb, Parcel reply) {
            Map<String, UsbDevice> devices = new TreeMap<>(usb.getDeviceList());
            status(reply, OK, null);
            reply.writeInt(devices.size());
            for (Map.Entry<String, UsbDevice> e : devices.entrySet()) {
                reply.writeString(e.getKey());
                reply.writeInt(usb.hasPermission(e.getValue()) ? 1 : 0);
            }
        }

        private void open(UsbManager usb, String name, int askSeconds, Parcel reply) {
            UsbDevice device = name == null ? null : usb.getDeviceList().get(name);
            if (device == null) {
                status(reply, NOT_ATTACHED, name + " is not attached");
                return;
            }
            if (!usb.hasPermission(device)) {
                if (askSeconds <= 0) {
                    status(reply, NOT_ALLOWED, "this app has no permission for " + name + " yet");
                    return;
                }
                int asked = ask(usb, device, askSeconds);
                if (asked != OK) {
                    status(reply, asked, asked == TIMED_OUT
                            ? "nobody answered Android's question within " + askSeconds + "s"
                            : "Android's question about " + name + " was declined");
                    return;
                }
            }
            UsbDeviceConnection connection = usb.openDevice(device);
            if (connection == null) {
                status(reply, FAILED, "Android would not open " + name);
                return;
            }
            // The connection's descriptor is duplicated into the reply, so
            // this app can let go of its own copy straight away: the device
            // stays open for as long as Termux holds the duplicate.
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.fromFd(connection.getFileDescriptor())) {
                status(reply, OK, null);
                reply.writeFileDescriptor(fd.getFileDescriptor());
                Log.i(TAG, "UsbLink: handed " + name + " to Termux");
            } catch (Exception e) {
                reply.setDataPosition(0);
                reply.setDataSize(0);
                status(reply, FAILED, "could not hand over " + name + ": " + e);
            } finally {
                connection.close();
            }
        }

        /**
         * Puts Android's permission question up and waits for the answer.
         * Because this app handles the device, the question carries a "Use
         * by default" box: ticking it makes this the lasting grant.
         */
        private int ask(UsbManager usb, final UsbDevice device, int seconds) {
            synchronized (ASKING) {
                if (usb.hasPermission(device)) {
                    return OK;
                }
                final String action = app.getPackageName() + ".USB_PERMISSION";
                final CountDownLatch answered = new CountDownLatch(1);
                final boolean[] granted = {false};
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (d != null && d.getDeviceName().equals(device.getDeviceName())) {
                            granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                            answered.countDown();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter(action);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                try {
                    // Mutable, because Android fills in the device and the
                    // answer; explicit, because Android 14 refuses a mutable
                    // PendingIntent that any app could intercept.
                    int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent result = PendingIntent.getBroadcast(app, 0,
                            new Intent(action).setPackage(app.getPackageName()),
                            mutable | PendingIntent.FLAG_UPDATE_CURRENT);
                    usb.requestPermission(device, result);
                    long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                    // The answer normally arrives as the broadcast above;
                    // checking the grant itself as well means a broadcast
                    // that goes astray costs nothing but the wait.
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (answered.await(500, TimeUnit.MILLISECONDS)) {
                            return granted[0] || usb.hasPermission(device) ? OK : NOT_ALLOWED;
                        }
                        if (usb.hasPermission(device)) {
                            return OK;
                        }
                    }
                    return TIMED_OUT;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return TIMED_OUT;
                } finally {
                    app.unregisterReceiver(receiver);
                }
            }
        }

        private static void status(Parcel reply, int status, String message) {
            reply.writeInt(status);
            reply.writeString(message);
        }
    }
}
