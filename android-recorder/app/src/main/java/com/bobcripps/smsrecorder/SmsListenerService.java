package com.bobcripps.smsrecorder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Service that listens for inbound and outbound SMS. Writes them to a CSV file and uploads the file
 * to a server.
 */
public class SmsListenerService extends Service implements FileUploadListener {
	private static final String TAG = SmsListenerService.class.getSimpleName()+"-Recorder";
	// preferences file for storing app persistent data
	public static final String PREFS_FILENAME = "recorder-prefs";
	// Store the last SMS id database ID autoincremented by the OS. Increments every time an SMS is received or sent
	// Avoids saving duplicates if the app is restarted
	private static final String CURRENT_ID_KEY = "currentId";
	// The uri for our listener observing the SMS database
	private static final String CONTENT_SMS = "content://sms";
	// Store the SMS's received and sent in this file
	private static final String SMS_FILE = "smsfile";
	// move the previous storage file to this one for upload. Do it inside the lock
	private static final String SMS_FILE_UPLOAD = "smsfile-upload";
	// Services can be started multiple times so only initialize it once
	private volatile boolean started = false;
	// Service creates a worker thread for handling SMS sent/received notifications
	private Thread smsQueueThread;
	// the listener places the SMS uri on this queue
	BlockingQueue<Uri> smsQueue = new LinkedBlockingQueue<>();
	// So we don't write duplicate SMS to the smsfile keep a set of unique ID
	// This is because we can get more than one notification for the same SMS
	// The ID is the system unique ID for the message
	// Possibly persist this Set although in reality it would need thousands of SMS with no
	// app restart for the Set to grow "large"
	private Set<Long> idSet = new HashSet<>();
	// For interaction with file upload completion callback
	private Object lock = new Object();
	// Separate class for file upload
	private FileUploadHandler fileUploadHandler;
	// Don't call file upload if an upload is still in progress
	private volatile boolean isUploading = false;

	// Mandatory override not used as our service is not bound
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		fileUploadHandler = new FileUploadHandler(this);
		Log.d(TAG, "onCreate()");
		// Create a thread that consumes a queue produced on by an SMS content observer
		// onStartCommand() kicks both off
		smsQueueThread = new Thread(new Runnable() {
			@Override
			public void run() {
				// If we have just been started manually and not at boot up
				// there could be SMS we have not read so they need persisting
				missedMessageCheck();
				// If there is an upload file present then try and upload it
				entryUploadFileCheck();
				for(;;) {
					try {
						// Block here. The observer queues the Uri's so no events are missed
						// The observer will be executed in the context of the application thread
						Uri uri = smsQueue.take();
						Log.d(TAG, "listener take uri = " + uri.toString());
						// The URI should contain an ID for the message although for inbound it
						// sometimes doesn't (returns -1) "raw" but by the time we read the store it
						// could have a valid ID
						long id = getIdFromUri(uri);
						// Check this ID is not < than the currentId
						overtakeCheck(id);
						// If the ID is not in the Set<Long> then read and persist the message
						if(!idSet.contains(id)){
							processMessages();
						}
					} catch (InterruptedException e) {
						Log.d(TAG, "listener InterruptedException = " + e.getMessage());
						return;
					}
				}
			}
		});
	}

	/**
	 * Called on entry to the listener thread hence not using the lock
	 */
	private void entryUploadFileCheck() {
		File smsFile = new File(getFilesDir() + "/" + SMS_FILE_UPLOAD);
		if(smsFile.exists()) {
			isUploading = true;
			fileUploadHandler.uploadFile(smsFile);
		}
	}

	/**
	 * Called by the OS and can be called multiple times
	 * hence the flag to see if we are already started
	 * @param intent
	 * @param flags
	 * @param startId
	 * @return START_STICKY as we intend to run in the background
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand()");
		if(started) {
			Log.d(TAG, "onStartCommand() aready started");
			return START_STICKY;
		} else {
			Log.d(TAG, "onStartCommand() not started");
			started = true;
			// Start the thread (consumer) and the observer (producer)
			smsQueueThread.start();
			configureSmsObserver();
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		smsQueueThread.interrupt();
	}

	/**
	 * Callback from the file upload handler
	 * if the upload succeeded then delete the file
	 * Do it all inside the lock
	 * @param success
	 */
	@Override
	public void fileUploaded(boolean success) {
		synchronized (lock) {
			isUploading = false;
			if(success) {
				File smsFile = new File(getFilesDir() + "/" + SMS_FILE_UPLOAD);
				Log.d(TAG, "fileUploaded() = " +smsFile.delete());
			} else {
				Log.d(TAG, "fileUploaded() failed");
			}
		}
	}

	/**
	 * The SMS content observer
	 */
	class SmsObserver extends ContentObserver {
		public SmsObserver(Handler handler) {
			super(handler);
		}

		@Override
		public void onChange(boolean selfChange) {
			this.onChange(selfChange, null);
		}

		/**
		 * Runs in the context of the application thread to just queue the URI
		 * @param selfChange
		 * @param uri
		 */
		@Override
		public void onChange(boolean selfChange, Uri uri) {
			try {
				Log.d(TAG, "onChange()- " + uri);
				smsQueue.put(uri);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Configure the SMS content observer
	 * Checks for first time into the application
	 */
	private void configureSmsObserver() {
		// Read the last SMS ID which is persisted
		SharedPreferences settings = getSharedPreferences(PREFS_FILENAME, 0);
		long currentId = settings.getLong(CURRENT_ID_KEY, 0);
		Log.d(TAG,"configureSmsObserver() enter currentId = " + currentId);
		// the current id is zero if it's first time in
		if (currentId == 0) {
			// Read the top message ID from the DB
			currentId = getTopMessageId();
			Log.d(TAG, "configureSmsObserver() getTopMessage() returned currentId = " + currentId);
			SharedPreferences.Editor editor = settings.edit();
			editor.putLong(CURRENT_ID_KEY, currentId);
			editor.commit();
		}
		// Register the observer
		getContentResolver().
				registerContentObserver(
						Uri.parse(CONTENT_SMS),
						true,
						new SmsObserver(new Handler()));
	}

	/**
	 * It's possible for a message to get to the state we persist it out of sequence
	 * so reset the currentId if we detect that condition
	 * @param id
	 */
	private void overtakeCheck(long id) {
		SharedPreferences settings = getSharedPreferences(PREFS_FILENAME, 0);
		long currentId = settings.getLong(CURRENT_ID_KEY, 0);
		// Id can be set negative by getIdFromUri()
		if(id > 0 && id < currentId) {
			Log.d(TAG, "overtakeCheck() reset current id from " + currentId + " - to " + id);
			SharedPreferences.Editor editor = settings.edit();
			editor.putLong(CURRENT_ID_KEY, id);
			editor.commit();
		}
	}

	/**
	 * read and persist messages with an ID > than the currentId
	 */
	private void processMessages() {
		SharedPreferences settings = getSharedPreferences(PREFS_FILENAME, 0);
		long currentId = settings.getLong(CURRENT_ID_KEY, 0);
		// Read the messages from the DB. They are only added to the list if they are in either inbox or sent
		List<SmsData> list = readMessagesUniqueAfterId(currentId);
		Log.d(TAG, "List size = " + list.size());
		if (list.size() > 0) {
			// Could be more than 1 message but the highest ID will be position zero in the list
			Log.d(TAG,"processMessages() save the id from list= " + list.get(0).getId());
			SharedPreferences.Editor editor = settings.edit();
			editor.putLong(CURRENT_ID_KEY, list.get(0).getId());
			editor.commit();
			// Save the list to file
			processSmsFile(list);
		}
	}

	/**
	 * The last part of a URI is the ID or it can be "raw"
	 * Detect if it's an integer and return it if it is or -1 if not
	 * @param uri
	 * @return
	 */
	private long getIdFromUri(Uri uri) {
		long ret = -1;
		// The URI is split with "/"
		String[] tokens = uri.toString().split("/");
		if (tokens.length > 0) {
			String lastToken = tokens[tokens.length - 1];
			Log.d(TAG,"getIdFromUri() = " + lastToken);
			// Code from stackoverflow. Alternative is a 3rd party lib or catch
			// number format exception
			NumberFormat formatter = NumberFormat.getInstance();
			ParsePosition pos = new ParsePosition(0);
			formatter.parse(lastToken, pos);
			if (lastToken.length() == pos.getIndex()) {
				ret = Long.parseLong(lastToken);
			}
		}
		Log.d(TAG, "getIdFromUri() id = " + ret);
		return ret;
	}

	/**
	 * Read SMS messages with a higher _ID than the parameter
	 * @param id
	 * @return
	 */
	private List<SmsData> readMessagesUniqueAfterId(long id) {
		List<SmsData> smsList = new ArrayList<>();
		Uri uri = Uri.parse(CONTENT_SMS);
		Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri,
					new String[]{
							Telephony.TextBasedSmsColumns.DATE,
							Telephony.TextBasedSmsColumns.BODY,
							Telephony.TextBasedSmsColumns.ADDRESS,
							Telephony.TextBasedSmsColumns.TYPE,
							BaseColumns._ID
					},
					BaseColumns._ID + ">?",
					new String[]{String.valueOf(id)}, null);
			if (cursor != null && cursor.getCount() > 0) {
				while (cursor.moveToNext()) {
					final long ldate = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE));
					final int type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.TYPE));
					final String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)).toString();
					final String number = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)).toString();
					final long currentId = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
					// the type is which folder. Currently just save inbox and sent
					if ((type == 1 || type == 2)) {
						// Bear in mind duplicate events of the same type for the same message ID can occur
						// To check for duplicates use Set<Long>
						// Only add it if the ID is not in the Set
						// This can can be used if we are not saving "pending" and "sending" state messages
						// If we are saving all states we will need
						// private Map<Long,Set<Integer>> theMap = new HashMap<>();
						// Where the Long is the message _ID and where
						// Set<Integer> is the type and must be unique
						// TODO periodic clear of lower Set elements to keep the heap size down
						// Possibly a tree so they are ordered for iterating
						if (!idSet.contains(currentId)) {
							idSet.add(currentId);
							SmsData smsData = new SmsData();
							smsData.setId(currentId);
							smsData.setDate(ldate);
							smsData.setBody(body);
							smsData.setNumber(number);
							smsData.setDirection(type == 1 ? SmsData.SMSDirection.In : SmsData.SMSDirection.Out);
							smsList.add(smsData);
						}
					}
				}
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return smsList;
	}

	/**
	 *
	 * @return long _ID of the top message in the SMS database
	 */
	private long getTopMessageId() {
		long topId = 0;
		Uri uri = Uri.parse(CONTENT_SMS);
		Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri,
					new String[]{
							BaseColumns._ID
					},
					null,
					null,
					BaseColumns._ID + " DESC LIMIT 1");
			if (cursor != null && cursor.getCount() > 0) {
				if (cursor.moveToNext()) {
					topId = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
					Log.d(TAG, "Top message id = " + topId);
				}
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return topId;
	}

	/**
	 * Process a list of SMS messages read from the SMS database and persist them to file
	 * Upload to the server if able to do so
	 * Uses two files one we write to and one we rename it to for upload
	 * This means there is always a file to write to
	 * Attempt an upload for every SMS(s) written to file
	 * @param smsData
	 */
	private void processSmsFile(List<SmsData> smsData) {
		try {
			synchronized (lock) {
				FileOutputStream outputStream = openFileOutput(SMS_FILE, Context.MODE_PRIVATE | Context.MODE_APPEND);
				// Reverse through the List which has oldest message last. Append to the file in that order
				for (int index = smsData.size() - 1; index >= 0; index--) {
					SmsData s = smsData.get(index);
					outputStream.write(s.toCsvString().getBytes());
				}
				outputStream.getFD().sync();
				outputStream.close();
				// Get File class for the file we write to and the one we rename to for upload
				File smsFile = new File(getFilesDir() + "/" + SMS_FILE);
				File smsUploadFile = new File(getFilesDir() + "/" + SMS_FILE_UPLOAD);
				if(!smsUploadFile.exists()) {
					Log.e(TAG,"processSmsFile() ready upload");
					// The upload file doesn't exist meaning first time in or the last upload
					// completion deleted it
					// rename the file we have appended to to the upload filename
					boolean success = smsFile.renameTo(smsUploadFile);
					if(success) {
						// Call the file upload handler to upload asynchronously
						smsUploadFile = new File(getFilesDir() + "/" + SMS_FILE_UPLOAD);
						fileUploadHandler.uploadFile(smsUploadFile);
					} else {
						Log.e(TAG,"processSmsFile() rename failed");
					}
				} else {
					Log.e(TAG,"processSmsFile() upload file exists uploading = " + isUploading);
					// The upload file is there so try and upload if not already uploading
					// This can happen first time into the app after a stop of the application
					// Also happens with SMS in rapid succession before the last upload completes
					if(!isUploading) {
						fileUploadHandler.uploadFile(smsUploadFile);
					}
				}
				//diagnosticPrintTopOfFile();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void diagnosticPrintTopOfFile() throws IOException {
		File file = new File(getFilesDir() + "/" + SMS_FILE);
		int fileLength = (int) file.length();
		FileInputStream inputStream = openFileInput(SMS_FILE);
		byte[] bytes = new byte[fileLength];
		Log.d(TAG, "Bytes read = " + inputStream.read(bytes, 0, fileLength));
		String contents = new String(bytes);
		int bytesToPrint = 200;
		if(fileLength < 200){
			bytesToPrint = fileLength;
		}
		contents = contents.substring(contents.length() - bytesToPrint);
		Log.d(TAG, contents);
		inputStream.close();

	}

	/**
	 * Called on thread entry
	 * Appends any missed messages to file and attempts upload
	 *
	 */
	private void missedMessageCheck() {
		SharedPreferences settings = getSharedPreferences(PREFS_FILENAME, 0);
		long currentId = settings.getLong(CURRENT_ID_KEY, 0);
		Log.d(TAG,"missedMessageCheck() enter currentId = " + currentId);
		long topMessageId = getTopMessageId();
		if(currentId < topMessageId) {
			processMessages();
		}
	}

}
