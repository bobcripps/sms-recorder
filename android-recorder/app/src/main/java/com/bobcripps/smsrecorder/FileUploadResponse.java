package com.bobcripps.smsrecorder;

public class FileUploadResponse {
	private boolean succeeded;
	private String message;

	public boolean isSucceeded() {
		return succeeded;
	}

	public void setSucceeded(boolean succeeded) {
		this.succeeded = succeeded;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
}
