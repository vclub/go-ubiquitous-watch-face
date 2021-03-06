/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.weatherwatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {

    public static final String WATCH_WEATHER_MSG_PATH = "/watch/data/weather";
    public static final String WATCH_WEATHER_READY = "ready";

    private static final String TEMPERATURE_SPACING = " ";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, NodeApi.NodeListener, MessageApi.MessageListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mMinTempPaint;
        Paint mMaxTempPaint;

        boolean mSwitchedToThisWatchFace;
        boolean mAmbient;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        static final String TIME_STRING = "00:00";
        static final String DATE_STRING = "Sun, JUN 15 2016";

        String mMinTemp = TEMPERATURE_SPACING + "0°";
        String mMaxTemp = TEMPERATURE_SPACING + "0°";

        int mTapCount;

        Calendar mCalendar;
        float mCenterXTimeOffset;
        float mCenterYTimeOffset;

        float mCenterXDateOffset;

        float mMaxTempWidth;

        Bitmap mForecastBitmap;
        float mCenterXForecastOffset;
        float mCenterYForecastOffset;

        float mXOffset;
        float mYOffset;

        float mTempTextHalfHeight;
        float mForecastBitmapHalfHeight;
        int mForecastBitmapWidth;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mCenterYForecastOffset = resources.getDimension(R.dimen.forecast_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(
                    WeatherWatchFace.this, R.color.background2));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(ContextCompat.getColor(
                    WeatherWatchFace.this, R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(ContextCompat.getColor(
                    WeatherWatchFace.this, R.color.date_text));
            mDatePaint.setTextSize(28);

            mForecastBitmap = ((BitmapDrawable) getResources().getDrawable(R.mipmap.ic_launcher)).getBitmap();

            mForecastBitmapWidth = mForecastBitmap.getWidth();
            mForecastBitmapHalfHeight = mForecastBitmap.getHeight() / 2f;

            mMaxTempPaint = createTextPaint(ContextCompat.getColor(WeatherWatchFace.this, R.color.digital_text));
            mMaxTempPaint.setTextSize(38);

            mMinTempPaint = createTextPaint(ContextCompat.getColor(WeatherWatchFace.this, R.color.digital_text_transparent));
            mMinTempPaint.setTextSize(38);

            mTime = new Time();
            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mSwitchedToThisWatchFace = true;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                Log.d(TAG, "onVisibilityChanged: try to connect google client");
                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();

                if(mGoogleApiClient != null && mGoogleApiClient.isConnected()){
                    Wearable.NodeApi.removeListener(mGoogleApiClient, this);
                    Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);

            mCenterXTimeOffset = mTimePaint.measureText(TIME_STRING) / 2;

            mCenterXDateOffset = mDatePaint.measureText(DATE_STRING) / 2;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WeatherWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // compute center
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float hourXOffset = centerX - mCenterXTimeOffset;
            float hourYOffset = centerY - mCenterYTimeOffset - 40;

            mTime.setToNow();
            // draw hour
            String hour = String.format(Locale.getDefault(), "%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(hour, hourXOffset, hourYOffset, mTimePaint);

            float dateXOffset = centerX - mCenterXDateOffset;
            float dateYOffset = centerY - mCenterYTimeOffset;

            SimpleDateFormat sdf = new SimpleDateFormat("E, MMM dd yyyy", Locale.getDefault());
            String date = sdf.format(mCalendar.getTime());
            canvas.drawText(date, dateXOffset, dateYOffset, mDatePaint);

            if (mForecastBitmap != null && !isInAmbientMode()) {
                canvas.drawLine(centerX - 40, centerY + 20, centerX + 40, centerY + 20, mTimePaint);
            }

            // Draw Date bitmap
            float forecastBitmapXOffset = centerX - mCenterXForecastOffset - 100;
            float forecastBitmapYOffset = centerY + mCenterYForecastOffset + 20;

            if (mForecastBitmap != null && !isInAmbientMode()) {
                canvas.drawBitmap(
                        mForecastBitmap,
                        forecastBitmapXOffset,
                        forecastBitmapYOffset,
                        null);
            }

            mMaxTempWidth = mMaxTempPaint.measureText(mMaxTemp);

            // Draw max temp
            if (mMaxTemp != null && !isInAmbientMode()) {
                canvas.drawText(
                        mMaxTemp,
                        forecastBitmapXOffset + mForecastBitmapWidth,
                        forecastBitmapYOffset + mForecastBitmapHalfHeight - mTempTextHalfHeight,
                        mMaxTempPaint);
            }

            if (mMinTemp != null && !isInAmbientMode()) {
                canvas.drawText(
                        mMinTemp,
                        forecastBitmapXOffset + mForecastBitmapWidth + mMaxTempWidth,
                        forecastBitmapYOffset + mForecastBitmapHalfHeight + mTempTextHalfHeight,
                        mMinTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {

            Log.d(TAG, "onConnected: ");

            Wearable.NodeApi.addListener(mGoogleApiClient, this);
            Wearable.MessageApi.addListener(mGoogleApiClient, this);

            if(mSwitchedToThisWatchFace) {
                sendReadyMessageToPhone();
                mSwitchedToThisWatchFace = false;
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: ");
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onPeerConnected(Node node) {
            sendReadyMessageToPhone();
        }

        private void sendReadyMessageToPhone(){
            Log.d(TAG, "sendReadyMessageToPhone: ");
            if(mGoogleApiClient.isConnected()) {
                new Thread(){
                    @Override
                    public void run() {
                        NodeApi.GetConnectedNodesResult nodesList =
                                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                        for(Node node : nodesList.getNodes()){
                            Wearable.MessageApi.sendMessage(
                                    mGoogleApiClient,
                                    node.getId(),
                                    WATCH_WEATHER_MSG_PATH,
                                    WATCH_WEATHER_READY.getBytes()).await();
                        }
                    }
                }.start();
            }
        }

        @Override
        public void onPeerDisconnected(Node node) {

        }

        private static final String TAG = "Engine";

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            Log.d(TAG, "onMessageReceived: ");
            if (messageEvent.getPath().equals(WATCH_WEATHER_MSG_PATH)){
                try{
                    byte[][] byteArrayMsgHolder = (byte[][])deserialize(messageEvent.getData());

                    mForecastBitmap = BitmapFactory.decodeByteArray(
                            byteArrayMsgHolder[0],
                            0,
                            byteArrayMsgHolder[0].length
                    );

                    mMinTemp = new String(byteArrayMsgHolder[1]);
                    mMaxTemp = new String(byteArrayMsgHolder[2]);

                    float minTempTextWidth = mMinTempPaint.measureText(mMinTemp);
                    mMaxTempWidth = mMaxTempPaint.measureText(mMaxTemp);
                    float totalTempTextWidth = minTempTextWidth + mMaxTempWidth;

                    mForecastBitmapWidth = mForecastBitmap.getWidth();
                    mCenterXForecastOffset = (mForecastBitmapWidth + totalTempTextWidth) / 2f;

                    mForecastBitmapHalfHeight = mForecastBitmap.getHeight() / 2f;

                    Rect bonds = new Rect();
                    mMaxTempPaint.getTextBounds(mMaxTemp, 0, mMaxTemp.length(), bonds);
                    mTempTextHalfHeight = bonds.height() / 2f;

                    invalidate();
                }catch (IOException | ClassNotFoundException e){
                    Log.e(TAG, Log.getStackTraceString(e) );
                }
            }
        }

        public Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
            ByteArrayInputStream b = new ByteArrayInputStream(bytes);
            ObjectInputStream o = new ObjectInputStream(b);
            return o.readObject();
        }
    }
}
