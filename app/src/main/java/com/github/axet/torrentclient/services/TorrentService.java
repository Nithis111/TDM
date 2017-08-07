package com.github.axet.torrentclient.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.RemoteViews;

import com.github.axet.androidlibrary.app.AlarmManager;
import com.github.axet.androidlibrary.widgets.OptimizationPreferenceCompat;
import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.BootActivity;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.torrentclient.app.Storage;
import com.github.axet.torrentclient.app.TorrentPlayer;

public class TorrentService extends Service {
    public static final String TAG = TorrentService.class.getSimpleName();

    public static final int NOTIFICATION_TORRENT_ICON = 1;
    public static final int NOTIFICATION_DOWNLOAD_ICON = 10; // 10 + number of torrents

    public static String TITLE = "TITLE";

    public static String UPDATE_NOTIFY = TorrentService.class.getCanonicalName() + ".UPDATE_NOTIFY";
    public static String SHOW_ACTIVITY = TorrentService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String PAUSE_BUTTON = TorrentService.class.getCanonicalName() + ".PAUSE_BUTTON";

    TorrentReceiver receiver;
    OptimizationPreferenceCompat.ServiceReceiver optimization;
    MediaSessionCompat msc;
    PendingIntent pause;
    Handler handler = new Handler();

    public static void startService(Context context, String title) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = shared.edit();
        edit.putBoolean(MainApplication.PREFERENCE_RUN, true);
        edit.commit();
        Intent i = new Intent(context, TorrentService.class).setAction(UPDATE_NOTIFY).putExtra(TITLE, title);
        context.startService(i);
    }

    public static void updateNotify(Context context, String title, String player, boolean playing) {
        Intent i = new Intent(UPDATE_NOTIFY)
                .putExtra(TITLE, title)
                .putExtra("player", player)
                .putExtra("playing", playing);
        context.sendBroadcast(i);
    }

    public static void stopService(Context context) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = shared.edit();
        edit.putBoolean(MainApplication.PREFERENCE_RUN, false);
        edit.commit();
        context.stopService(new Intent(context, TorrentService.class));
    }

    public static void notifyDone(Context context, Storage.Torrent t, int i) {
        String title = context.getString(R.string.app_name);
        String text = t.name();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_stat_downloaded)
                .setContentTitle(title)
                .setContentText(text);

        PendingIntent main = PendingIntent.getService(context, 0,
                new Intent(context, TorrentService.class).setAction(TorrentService.SHOW_ACTIVITY),
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle();
        style.setBigContentTitle(title);
        style.bigText(text);

        builder.setContentIntent(main);
        builder.setAutoCancel(true);
        builder.setLights(Color.BLUE, 500, 500);
        builder.setStyle(style);
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(alarmSound);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_DOWNLOAD_ICON + i, builder.build());
    }

    public class TorrentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UPDATE_NOTIFY)) {
                showNotificationAlarm(true, intent);
                return;
            }
            Log.d(TAG, "TorrentReceiver " + intent);
            MediaButtonReceiver.handleIntent(msc, intent);
        }
    }

    public TorrentService() {
    }

    boolean isRunning() {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        return shared.getBoolean(MainApplication.PREFERENCE_RUN, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        OptimizationPreferenceCompat.REFRESH = AlarmManager.MIN5;
        optimization = new OptimizationPreferenceCompat.ServiceReceiver(this, getClass());

        receiver = new TorrentReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UPDATE_NOTIFY);
        registerReceiver(receiver, filter);

        if (!isRunning()) {
            stopSelf();
            return;
        }

        startForeground(NOTIFICATION_TORRENT_ICON, buildNotification(getString(R.string.tap_restart), "", false));
    }

    MainApplication getApp() {
        return ((MainApplication) getApplication());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand " + intent);

        if (!isRunning()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (optimization.onStartCommand(intent, flags, startId)) {
            Log.d(TAG, "onStartCommand restart");
            BootActivity.createApplication(this);
        }

        if (intent != null) {
            String a = intent.getAction();
            if (a != null) {
                if (a.equals(PAUSE_BUTTON)) {
                    ;
                }
                if (a.equals(UPDATE_NOTIFY)) {
                    showNotificationAlarm(true, intent);
                }
                if (a.equals(SHOW_ACTIVITY)) {
                    MainActivity.startActivity(this);
                }
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public class Binder extends android.os.Binder {
        public TorrentService getService() {
            return TorrentService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");

        if (optimization != null) {
            optimization.close();
            optimization = null;
        }

        sendBroadcast(new Intent(MainApplication.SAVE_STATE));

        stopForeground(false);

        showNotificationAlarm(false, new Intent());

        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }

        headset(false, false);
    }

    Notification buildNotification(String title, String player, boolean playing) {
        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, TorrentService.class).setAction(SHOW_ACTIVITY),
                PendingIntent.FLAG_UPDATE_CURRENT);

//        PendingIntent pe = PendingIntent.getService(this, 0,
//                new Intent(this, TorrentService.class).setAction(PAUSE_BUTTON),
//                PendingIntent.FLAG_UPDATE_CURRENT);

        pause = PendingIntent.getBroadcast(this, 0,
                new Intent(TorrentPlayer.PLAYER_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteViews view = new RemoteViews(getPackageName(), MainApplication.getTheme(getBaseContext(),
                R.layout.notifictaion_recording_light,
                R.layout.notifictaion_recording_dark));

        //boolean pause = false;//getStorage().isPause();

        view.setOnClickPendingIntent(R.id.status_bar_latest_event_content, main);
        view.setTextViewText(R.id.notification_text, title);
        //view.setOnClickPendingIntent(R.id.notification_pause, pe);
        //view.setImageViewResource(R.id.notification_pause, pause ? R.drawable.play : R.drawable.pause);
        if (player == null || player.isEmpty()) {
            view.setViewVisibility(R.id.notification_play, View.GONE);
            view.setViewVisibility(R.id.notification_playing, View.GONE);
            headset(false, playing);
        } else {
            view.setViewVisibility(R.id.notification_play, View.VISIBLE);
            view.setViewVisibility(R.id.notification_playing, View.VISIBLE);
            view.setTextViewText(R.id.notification_play, player);
            view.setImageViewResource(R.id.notification_playing, playing ? R.drawable.ic_pause_24dp : R.drawable.ic_play_arrow_black_24dp);
            headset(true, playing);
        }
        view.setOnClickPendingIntent(R.id.notification_playing, pause);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentText(title)
                .setContent(view);

        if (Build.VERSION.SDK_INT >= 21)
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        return builder.build();
    }

    public void showNotificationAlarm(boolean show, Intent intent) {
        String title = intent.getStringExtra(TITLE);
        String play = intent.getStringExtra("player");
        boolean playing = intent.getBooleanExtra("playing", false);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!show) {
            notificationManager.cancel(NOTIFICATION_TORRENT_ICON);
        } else {
            notificationManager.notify(NOTIFICATION_TORRENT_ICON, buildNotification(title, play, playing));
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved");
        optimization.onTaskRemoved(rootIntent);
    }

    void headset(boolean b, final boolean playing) {
        if (b) {
            if (msc == null) {
                Log.d(TAG, "headset mediabutton on");
                ComponentName tr = new ComponentName(this, TorrentReceiver.class);
                msc = new MediaSessionCompat(this, TAG, tr, null);
                final MainApplication app = (MainApplication) getApplicationContext();
                msc.setCallback(new MediaSessionCompat.Callback() {
                    @Override
                    public void onPlay() {
                        pause();
                    }

                    @Override
                    public void onPause() {
                        pause();
                    }

                    @Override
                    public void onStop() {
                        app.playerStop();
                    }

                    @Override
                    public void onSkipToNext() {
                        if (app.player == null)
                            return;
                        int i = app.player.getPlaying() + 1;
                        if (i >= app.player.getSize())
                            i = 0;
                        app.player.play(i);
                    }

                    @Override
                    public void onSkipToPrevious() {
                        if (app.player == null)
                            return;
                        int i = app.player.getPlaying() - 1;
                        if (i < 0)
                            i = app.player.getSize() - 1;
                        app.player.play(i);
                    }
                });
                msc.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
                msc.setActive(true);
                // bug, when after device boot we have to set playing state to 'playing' to make mediabutton work
                msc.setPlaybackState(buildState(true));
            }
            msc.setPlaybackState(buildState(playing));
        } else {
            if (msc != null) {
                Log.d(TAG, "headset mediabutton off");
                msc.release();
                msc = null;
            }
        }
    }

    PlaybackStateCompat buildState(boolean playing) {
        return new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, 0, 1)
                .build();
    }

    void pause() {
        try {
            pause.send();
        } catch (PendingIntent.CanceledException e) {
            Log.d(TAG, "canceled expcetion", e);
        }
    }
}
