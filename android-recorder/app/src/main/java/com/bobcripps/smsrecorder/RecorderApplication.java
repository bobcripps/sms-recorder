package com.bobcripps.smsrecorder;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

public class RecorderApplication extends Application {
	private static final String TAG = RecorderApplication.class.getSimpleName()+"-Recorder";

	private static RecorderApplication recorderApplication;

	public static RecorderApplication getApplication() {
		return RecorderApplication.recorderApplication;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		RecorderApplication.recorderApplication = this;
		startService();
		Log.d(TAG, "onCreate()");
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
	}

	public final void startService() {
		Intent serviceIntent = new Intent(this, SmsListenerService.class);
		startService(serviceIntent);
	}
}
