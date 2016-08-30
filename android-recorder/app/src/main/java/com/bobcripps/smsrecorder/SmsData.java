package com.bobcripps.smsrecorder;

import java.util.Calendar;

public class SmsData {

	public enum SMSDirection {In, Out}

	private String number;
	private String body;
	private long date;
	private SMSDirection direction;
	private long id;

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public SMSDirection getDirection() {
		return direction;
	}

	public void setDirection(SMSDirection direction) {
		this.direction = direction;
	}

	public long getDate() {
		return date;
	}

	public void setDate(long date) {
		this.date = date;
	}

	public String getNumber() {
		return number;
	}

	public void setNumber(String number) {
		this.number = number;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public String toCsvString() {
		StringBuilder builder = new StringBuilder();
		builder.append("\"" + number + "\",")
				.append("\"" + direction + "\",")
				.append("\"" + body + "\",")
				.append("\"" + id + "\",")
				.append("\"" + getDateString() + "\"\n");
		return builder.toString();
	}

	private String getDateString() {
		Calendar cl = Calendar.getInstance();
		cl.setTimeInMillis(date);
		String date = "" + cl.get(Calendar.DAY_OF_MONTH) + ":" + cl.get(Calendar.MONTH) + ":" + cl.get(Calendar.YEAR);
		String time = "" + cl.get(Calendar.HOUR_OF_DAY) + ":" + cl.get(Calendar.MINUTE) + ":" + cl.get(Calendar.SECOND);
		return date + " " + time;
	}

}