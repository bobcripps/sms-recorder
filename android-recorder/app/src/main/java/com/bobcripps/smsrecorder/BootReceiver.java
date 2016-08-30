package com.bobcripps.smsrecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
	private static final String TAG = BootReceiver.class.getSimpleName()+"-Recorder";
	@Override
	public final void onReceive(final Context pContext, final Intent pIntent) {
		RecorderApplication.getApplication().startService();
	}
}
