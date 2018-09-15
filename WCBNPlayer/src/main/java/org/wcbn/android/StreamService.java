package org.wcbn.android;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import net.moraleboost.streamscraper.ScrapeException;
import net.moraleboost.streamscraper.Scraper;
import net.moraleboost.streamscraper.Stream;
import net.moraleboost.streamscraper.scraper.IceCastScraper;

import org.wcbn.android.station.Station;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Android Service that handles background music playback and metadata fetch.
 */
public class StreamService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String TAG = "WCBNStreamService";

    public static final String ACTION_PLAY_PAUSE = "org.wcbn.android.intent.ACTION_PLAY_PAUSE";
    public static final String ACTION_STOP = "org.wcbn.android.intent.ACTION_STOP";
    public static final String NOTIFICATION_CHANNEL_ID = "com.wcbn.WCBNPlayer.service";
    public static final long DELAY_MS = 10000;

    // TODO: Move quality handling to WCBN-specific code.
    public static class Quality {
        public static final String MID = "0";
        static final String HI = "1";
        public static final String HD = "2";

        static String getUri(String quality, Resources res) {
            return res.getStringArray(R.array.stream_uri)[Integer.parseInt(quality)];
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if(mIsPaused && !mIsPreparing && mPlayer != null) {
                    startPlayback();
                    mPlayer.setVolume(1.0f, 1.0f);
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                if(mPlayer != null && !mIsPreparing) {
                    stopPlayback();
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if(mPlayer.isPlaying()) {
                    pausePlayback();
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mPlayer.isPlaying()) {
                    mPlayer.setVolume(0.1f, 0.1f);
                }
                break;
        }
    }

    private String mStreamUri;
    private MediaPlayer mPlayer;
    private final IBinder mBinder = new StreamBinder();
    private OnStateUpdateListener mUpdateListener;
    private Handler mMetadataHandler = new Handler();
    private Runnable mMetadataRunnable = new MetadataUpdateRunnable();
    private NotificationHelper mNotificationHelper;
    private NotificationManager mNotificationManager;
    private Scraper mScraper = new IceCastScraper();
    private Station mStation;
    private Bitmap mLargeAlbumArt;
    private StreamExt mCurStream;
    private boolean mIsPaused = true, mIsPreparing = false, mIsForeground = false, mRefresh = false,
        mGrabAlbumArt;
    private Bundle mPersistData = new Bundle();

    public StreamService() {
        super();
        mStation = Utils.getStation();
    }

    class StreamBinder extends Binder {
        StreamService getService() {
            return StreamService.this;
        }
    }

    public void reset() {
        stopForeground(true);
        mPlayer.release();
        initPlayer();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, intent.getAction());

            if(ACTION_PLAY_PAUSE.equals(intent.getAction())) {
                if(mIsPaused) {
                    startPlayback();
                }
                else {
                    pausePlayback();
                }
            }
            else if(ACTION_STOP.equals(intent.getAction())) {
                stopPlayback();
            }
            else if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                if(!mIsPaused)
                    pausePlayback();
            }
        }
    };

    public boolean prepare() {
        mIsPreparing = true;
        try {
            mNotificationHelper.setPlaying(true);
            mIsPaused = false;
            startForeground(1, mNotificationHelper.getNotification());
            mIsForeground = true;
            mMetadataHandler.post(mMetadataRunnable);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PLAY_PAUSE);
            filter.addAction(ACTION_STOP);
            filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(mReceiver, filter);
            if(mPlayer.isPlaying())
                reset();
            mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mIsPreparing = false;
                    // Check if we're not paused in case the user presses the pause button during
                    // preparation.
                    if(!mIsPaused)
                        startPlayback();
                }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.d(TAG, "ERROR: "+what+" "+extra);
                    mIsPreparing = false;
                    if(mUpdateListener != null)
                        mUpdateListener.onMediaError(mp, what, extra);
                    return true;
                }
            });
            mPlayer.prepareAsync();
            return true;
        } catch(IllegalStateException e) {
            mIsPreparing = false;
            e.printStackTrace();
            return false;
        }
    }

    public void startPlayback() {

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int result = 0;
        if (audioManager != null) {
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }

        mPlayer.start();
        mIsPaused = false;
        mIsPreparing = false;
        mNotificationHelper.setPlaying(true);
        startForeground(1, mNotificationHelper.getNotification());
        if(mUpdateListener != null)
            mUpdateListener.onMediaPlay();
    }

    public void stopPlayback() {
        mIsPaused = true;
        mIsPreparing = false;
        if(mUpdateListener != null)
            mUpdateListener.onMediaStop();
        stopForeground(true);
        mIsForeground = false;

        if(!mRefresh)
            mMetadataHandler.removeCallbacks(mMetadataRunnable);

        reset();

        try {
            unregisterReceiver(mReceiver);
        } catch(IllegalArgumentException e) {
            e.printStackTrace(); // Already unregistered
        }
    }

    public void pausePlayback() {
        mPlayer.pause();
        mIsPaused = true;
        mIsPreparing = false;
        mNotificationHelper.setPlaying(false);
        mNotificationManager.notify(1, mNotificationHelper.getNotification());
        if(mUpdateListener != null)
            mUpdateListener.onMediaPause();
    }

    public boolean isPlaying() {
        return !mIsPaused;
    }

    public boolean isPreparing() {
        return mIsPreparing;
    }

    public void initPlayer() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        mPlayer = new MediaPlayer();

        String quality = prefs.getString("quality", Quality.HI);
        mGrabAlbumArt = prefs.getBoolean("grab_album_art", true);

        mStreamUri = Quality.getUri(quality, getResources());
        Log.d(TAG, "Using URI: "+mStreamUri);

        mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

        try {
            mPlayer.setDataSource(this, Uri.parse(mStreamUri));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        initPlayer();

        mLargeAlbumArt = BitmapFactory.
                decodeResource(getResources(),
                R.drawable.logo_large);

        mNotificationHelper = new NotificationHelper();
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//        if (VERSION.SDK_INT >= VERSION_CODES.O) {
//            mNotificationManager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID, "WCBN Service", IMPORTANCE_DEFAULT));
//            }
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if(mPlayer != null)
            mPlayer.release();
        stopForeground(true);
    }

    private class NotificationHelper {
        private final NotificationCompat.Builder mBuilderPlaying, mBuilderPaused;
        private NotificationCompat.Builder mCurrentBuilder;
        private String mTitle, mText, mSubText;
        private Bitmap mIcon;
        private final PendingIntent mPlayPauseIntent, mStopIntent;

        NotificationHelper() {
//            String channelId = "1";
            mBuilderPlaying = new NotificationCompat.Builder(getApplicationContext())
//                .setChannelId(channelId)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(0)
                .setSmallIcon(R.drawable.ic_stat_notify_notification);
            mBuilderPaused = new NotificationCompat.Builder(getApplicationContext())
//                .setChannelId(channelId)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(0)
                .setSmallIcon(R.drawable.ic_stat_notify_notification);

            mBuilderPlaying.setPriority(NotificationCompat.PRIORITY_MAX);
            mBuilderPaused.setPriority(NotificationCompat.PRIORITY_MAX);

            Intent resultIntent = new Intent(getApplicationContext(), MainActivity.class);
            TaskStackBuilder stackBuilder = TaskStackBuilder.create(getApplicationContext());
            stackBuilder.addParentStack(MainActivity.class);
            stackBuilder.addNextIntent(resultIntent);
            PendingIntent resultPendingIntent =
                    stackBuilder.getPendingIntent(
                            0,
                            PendingIntent.FLAG_UPDATE_CURRENT
                    );
            mBuilderPlaying.setContentIntent(resultPendingIntent);
            mBuilderPaused.setContentIntent(resultPendingIntent);

            Intent playPause = new Intent(ACTION_PLAY_PAUSE);
            Intent stop = new Intent(ACTION_STOP);
            mPlayPauseIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, playPause, 0);
            mStopIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, stop, 0);
            mBuilderPlaying.addAction(R.drawable.btn_playback_pause_dark,
                    getString(R.string.pause),
                    mPlayPauseIntent);
            mBuilderPaused.addAction(R.drawable.btn_playback_play_dark,
                    getString(R.string.play),
                    mPlayPauseIntent);
            mBuilderPlaying.addAction(R.drawable.btn_playback_stop_dark,
                    getString(R.string.stop),
                    mStopIntent);
            mBuilderPaused.addAction(R.drawable.btn_playback_stop_dark,
                    getString(R.string.stop),
                    mStopIntent);

            mCurrentBuilder = mBuilderPlaying;
        }

        private void updateBuilder() {
            mCurrentBuilder.setLargeIcon(mIcon);
            mCurrentBuilder.setContentTitle(mTitle);
            mCurrentBuilder.setContentText(mText);
            mCurrentBuilder.setSubText(mSubText);
        }

        public void setBitmap(Bitmap icon) {
            mIcon = icon;
        }

        public void setTitle(String title) {
            mTitle = title;
        }

        public void setText(String text) {
            mText = text;
        }

        public void setSubText(String subText) {
            mSubText = subText;
        }

        // Notification action update. Only available on JELLY_BEAN and above.
        public void setPlaying(boolean playing) {
            if(playing) {
                mCurrentBuilder = mBuilderPlaying;
            }
            else {
                mCurrentBuilder = mBuilderPaused;
            }
        }

        public Notification getNotification() {
            updateBuilder();
            return mCurrentBuilder.build();
        }

    }

    private class MetadataUpdateRunnable implements Runnable {
        @Override
        public void run() {
            new MetadataUpdateTask().execute();
        }
    }

    private class MetadataUpdateTask extends AsyncTask<Stream, Void, Stream> {

        @Override
        protected Stream doInBackground(Stream... previousStream) {
            try {
                List<Stream> streams = mScraper.scrape(new URI(mStreamUri));
                StreamExt stream = mStation.fixMetadata(streams);

                // Check if we're on the same song. If not, refresh metadata.
                if(mCurStream == null || !(mCurStream.getCurrentSong()
                        .equals(stream.getCurrentSong()))) {
                    mCurStream = stream;

                    if(mGrabAlbumArt) {
                        ItunesScraper scraper = new ItunesScraper(stream.getCurrentSong() + " " +
                        stream.getArtist(), "song");
                        mLargeAlbumArt = scraper.getLargeAlbumArt();
                        // Try getting the album's picture instead of the specific song picture
                        if(mLargeAlbumArt == null) {
                            scraper = new ItunesScraper(stream.getCurrentSong() + " " +
                                    stream.getArtist(), "album");
                            mLargeAlbumArt = scraper.getLargeAlbumArt();
                        }
                        if(mLargeAlbumArt == null) {
                            scraper = new ItunesScraper(stream.getAlbum(), "album");
                            mLargeAlbumArt = scraper.getLargeAlbumArt();
                        }
                    }
                    return stream;
                }
                return null;
            } catch(URISyntaxException e) {
                e.printStackTrace();
                return null;
            } catch(ScrapeException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        public void onPostExecute(Stream result) {
            if(result != null) {
                // Finally, resort back to the placeholder album art
                if(mLargeAlbumArt == null) {
                    mLargeAlbumArt = BitmapFactory
                            .decodeResource(getApplicationContext().getResources(),
                                    R.drawable.logo_large);
                    mNotificationHelper.setBitmap(BitmapFactory
                            .decodeResource(getApplicationContext().getResources(),
                                    R.drawable.ic_menu_logo));
                }

                else if(mGrabAlbumArt) {
                    mNotificationHelper.setBitmap(mLargeAlbumArt);
                }

                mNotificationHelper.setTitle(mStation.getSongName((StreamExt) result
                        , getApplicationContext()));
                mNotificationHelper.setText(mStation.getArtistName((StreamExt) result
                        , getApplicationContext()));
                mNotificationHelper.setSubText(mStation.getDescription((StreamExt) result
                        , getApplicationContext()));

                if(mIsPaused) {
                    mNotificationHelper.setPlaying(false);
                }
                else {
                    mNotificationHelper.setPlaying(true);
                }

                if(mIsForeground)
                    mNotificationManager.notify(1, mNotificationHelper.getNotification());

                if(mUpdateListener != null)
                    mUpdateListener.updateTrack(result, mStation, mLargeAlbumArt);
            }
            mMetadataHandler.postDelayed(mMetadataRunnable, DELAY_MS);
        }
    }

    public void setOnStateUpdateListener(OnStateUpdateListener listener) {
        mUpdateListener = listener;
    }

    public Bitmap getAlbumArt() {
        return mLargeAlbumArt;
    }

    public Station getStation() {
        return mStation;
    }

    public StreamExt getStream() {
        return mCurStream;
    }

    public void setMetadataRefresh(boolean refresh) {
        if(refresh) {
            mMetadataHandler.post(mMetadataRunnable);
        }
        else if(!mIsForeground) {
            mMetadataHandler.removeCallbacks(mMetadataRunnable);
        }
        mRefresh = refresh;
    }

    public Bundle getPersistData() {
        return mPersistData;
    }

    public interface OnStateUpdateListener {
        void onMediaError(MediaPlayer mp, int what, int extra);
        void onMediaPlay();
        void onMediaPause();
        void onMediaStop();
        void updateTrack(Stream stream, Station station, Bitmap albumArt);
    }
}
