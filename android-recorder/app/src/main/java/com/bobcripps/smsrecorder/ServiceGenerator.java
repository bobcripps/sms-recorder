package com.bobcripps.smsrecorder;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

public class ServiceGenerator {

	public static final String API_BASE_URL = "http://192.168.0.9:8080/bobcripps/";

	private static OkHttpClient.Builder httpClient = new OkHttpClient.Builder();

	private static Retrofit.Builder builder = new Retrofit.Builder()
					.baseUrl(API_BASE_URL);
					// From retrofit sample not sure why so leave commented out but we don't need it
					// TODO work out if it's useful
					//.addConverterFactory(GsonConverterFactory.create());

	public static <S> S createService(Class<S> serviceClass) {
		Retrofit retrofit = builder.client(httpClient.build()).build();
		return retrofit.create(serviceClass);
	}
}