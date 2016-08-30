package com.bobcripps.smsrecorder;


import android.util.Log;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public class FileUploadHandler {
	private static String TAG = "Recorder";
	private final FileUploadListener fileUploadListener;

	public interface FileUploadService {
		@Multipart
		@POST("fileupload.php")
		Call<ResponseBody> upload(@Part("description") RequestBody description, @Part MultipartBody.Part file);
	}

	public FileUploadHandler(FileUploadListener fileUploadListener) {
		this.fileUploadListener = fileUploadListener;
	}

	public void uploadFile(File file) {
		// create upload service client
		FileUploadService service = ServiceGenerator.createService(FileUploadService.class);
		// create RequestBody instance from file
		RequestBody requestFile = RequestBody.create(MediaType.parse("multipart/form-data"), file);
		// MultipartBody.Part is used to send also the actual file name
		MultipartBody.Part body = MultipartBody.Part.createFormData("smslog", file.getName(), requestFile);
		// add another part within the multipart request
		String descriptionString = "SMS file for upload";
		RequestBody description = RequestBody.create(MediaType.parse("multipart/form-data"), descriptionString);

		// finally, execute the request
		Call<ResponseBody> call = service.upload(description, body);
		call.enqueue(new Callback<ResponseBody>() {
			@Override
			public void onResponse(Call<ResponseBody> call,
								   Response<ResponseBody> response) {
				Log.v(TAG, "success");
				try {
					byte[] bytes = response.body().bytes();
					Gson gson = new Gson();
					String json = new String(bytes);
					FileUploadResponse result = gson.fromJson(json, FileUploadResponse.class);
					Log.d(TAG, result.getMessage() + " - " + result.isSucceeded());
					if (result.isSucceeded()) {
						fileUploadListener.fileUploaded(true);
					} else {
						fileUploadListener.fileUploaded(false);
					}
				} catch (IOException e) {
					fileUploadListener.fileUploaded(false);
					Log.d(TAG, e.getMessage());
				}
			}

			@Override
			public void onFailure(Call<ResponseBody> call, Throwable t) {
				Log.d(TAG, t.getMessage());
				fileUploadListener.fileUploaded(false);
			}
		});
	}
}
