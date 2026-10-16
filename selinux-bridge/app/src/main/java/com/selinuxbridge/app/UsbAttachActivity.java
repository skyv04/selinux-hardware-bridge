package com.selinuxbridge.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * Makes this app something Android can remember for a smart-card reader.
 *
 * Android keeps a USB permission only until the device is unplugged, so
 * Termux has to ask again on every plug-in -- and the question lands on the
 * phone's own screen, whatever the person is looking at. The one lasting
 * grant Android offers is the default handler for a device: an activity
 * that answers USB_DEVICE_ATTACHED for it (res/xml/usb_smart_card.xml).
 * Tick "Always" once when the key is plugged in, and from then on Android
 * grants that key to this app by itself every time it is attached, then
 * starts this activity -- see UsbLinkReceiver for how the key reaches the
 * Debian side.
 *
 * There is nothing to show, so it finishes at once, before its window
 * (Theme.NoDisplay) could exist.
 */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
