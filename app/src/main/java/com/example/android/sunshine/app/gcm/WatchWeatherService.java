package com.example.android.sunshine.app.gcm;


import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

public class WatchWeatherService extends WearableListenerService
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    private static final String TAG = "WatchWeatherService";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent.getPath().equals(WATCH_WEATHER_MSG_PATH)) {
            if (new String(messageEvent.getData()).equals(WATCH_WEATHER_MSG_PATH)) {
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            byte[][] byteArrayMsgHolder = getTodaysForecastData();
                            byte[] serializedByteArrayMsgHolder = serialize(byteArrayMsgHolder);
                            sendWeatherToWatch(serializedByteArrayMsgHolder);
                        } catch (IOException e) {
                            Log.e(TAG, Log.getStackTraceString(e));
                        }
                    }
                };
            }
        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private byte[][] getTodaysForecastData() {
        String location = Utility.getPreferredLocation(this);
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                location, System.currentTimeMillis());
        Cursor data = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null, null,
                WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");
        if (data == null) {
            return null;
        }

        if (!data.moveToFirst()) {
            data.close();
            return null;
        }

        int weatherId = data.getInt(INDEX_WEATHER_ID);
        int weatherArtResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
        double maxTemp = data.getDouble(INDEX_MAX_TEMP);
        double minTemp = data.getDouble(INDEX_MIN_TEMP);

        String formattedMaxTemperature = Utility.formatTemperature(this, maxTemp);
        String formattedMinTemperature = Utility.formatTemperature(this, minTemp);
        data.close();

        Bitmap forecastBitmap = BitmapFactory.decodeResource(getResources(), weatherArtResourceId);

        forecastBitmap = Bitmap.createScaledBitmap(
                forecastBitmap,
                (int) getResources().getDimension(R.dimen.watch_today_icon),
                (int) getResources().getDimension(R.dimen.watch_today_icon),
                false);
        byte[] imageByteArray = convertBitmapToByteArray(forecastBitmap);
        byte[] minTempByteArray = formattedMinTemperature.getBytes();
        byte[] maxTempByteArray = formattedMaxTemperature.getBytes();

        return new byte[][]{imageByteArray, minTempByteArray, maxTempByteArray};
    }

    public static final String WATCH_WEATHER_MSG_PATH = "/watch/data/weather";
    public static final String WATCH_WEATHER_READY = "ready";

    private void sendWeatherToWatch(final byte[] message) {

//        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WATCH_WEATHER_MSG_PATH);
//
//        putDataMapRequest.getDataMap().putInt("max_temp", 10);
//
//
//        Asset asset = createAssetFromBitmap(bitmap);
//        putDataMapRequest.getDataMap().putAsset("icon", asset);
//
//        PutDataRequest request = putDataMapRequest.asPutDataRequest();
//
//        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
//                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
//                    @Override
//                    public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
//                        if (!dataItemResult.getStatus().isSuccess()){
//
//                        }else {
//
//                        }
//                    }
//                });

        if (mGoogleApiClient.isConnected()) {
            NodeApi.GetConnectedNodesResult nodesResult =
                    Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

            for (Node node : nodesResult.getNodes()) {
                Wearable.MessageApi.sendMessage(
                        mGoogleApiClient,
                        node.getId(),
                        WATCH_WEATHER_MSG_PATH,
                        message
                ).await();
            }
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    private byte[] convertBitmapToByteArray(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = null;

        try {
            byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            return byteArrayOutputStream.toByteArray();
        } finally {
            try {
                if (byteArrayOutputStream != null) {
                    byteArrayOutputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "convertBitmapToByteArray: " + Log.getStackTraceString(e));
            }
        }
    }

    private byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ObjectOutputStream o = new ObjectOutputStream(b);
        o.writeObject(object);
        return b.toByteArray();
    }
}
