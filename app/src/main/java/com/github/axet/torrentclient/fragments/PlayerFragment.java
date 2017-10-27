package com.github.axet.torrentclient.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.github.axet.androidlibrary.app.Storage;
import com.github.axet.androidlibrary.services.FileProvider;
import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.torrentclient.app.TorrentPlayer;
import com.github.axet.torrentclient.services.TorrentContentProvider;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.List;

import libtorrent.Libtorrent;

public class PlayerFragment extends Fragment implements MainActivity.TorrentFragmentInterface {
    View v;
    ListView list;
    View download;
    View empty;
    Files files;
    String torrentName;
    long pendindBytesUpdate; // update every new byte
    long pendindBytesLengthUpdate;
    TorrentPlayer player;
    TorrentPlayer.Receiver playerReceiver;
    ImageView play;
    View prev;
    View next;
    TextView playerPos;
    TextView playerDur;
    SeekBar seek;
    Handler handler = new Handler();

    public class Files extends BaseAdapter {
        public int selected = -1;

        public String getFileType(int index) {
            TorrentPlayer.PlayerFile f = getItem(index);
            return TorrentPlayer.getType(f);
        }

        @Override
        public int getCount() {
            if (player == null)
                return 0;
            return player.getSize();
        }

        @Override
        public TorrentPlayer.PlayerFile getItem(int i) {
            return player.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        boolean single(File path) {
            return path.getName().equals(path);
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            LayoutInflater inflater = LayoutInflater.from(getContext());

            if (view == null) {
                view = inflater.inflate(R.layout.torrent_player_item, viewGroup, false);
            }

            if (player != null && player.getPlaying() == i) {
                view.setBackgroundColor(Color.YELLOW);
            } else {
                if (selected == i) {
                    view.setBackgroundColor(Color.LTGRAY);
                } else {
                    view.setBackgroundColor(Color.TRANSPARENT);
                }
            }

            final long t = getArguments().getLong("torrent");

            final TorrentPlayer.PlayerFile f = getItem(i);

            TextView percent = (TextView) view.findViewById(R.id.torrent_files_percent);
            percent.setEnabled(false);
            if (!f.isLoaded())
                MainApplication.setTextNA(percent, f.getPercent() + "%");
            else
                MainApplication.setTextNA(percent, "100%");

            TextView size = (TextView) view.findViewById(R.id.torrent_files_size);
            size.setText(MainApplication.formatSize(getContext(), f.getLength()));

            TextView folder = (TextView) view.findViewById(R.id.torrent_files_folder);
            TextView file = (TextView) view.findViewById(R.id.torrent_files_name);
            TextView archive = (TextView) view.findViewById(R.id.torrent_files_archive);
            TextView archiveEnd = (TextView) view.findViewById(R.id.torrent_files_archive_end);

            if (f.file != null && f.index == 0) {
                archive.setVisibility(View.VISIBLE);
                archive.setText(FilenameUtils.getExtension(f.tor.file.getPath()));
            } else {
                archive.setVisibility(View.GONE);
            }
            if (f.file != null && f.index == (f.count - 1)) {
                archiveEnd.setVisibility(View.VISIBLE);
                archiveEnd.setText(FilenameUtils.getExtension(f.tor.file.getPath()));
            } else {
                archiveEnd.setVisibility(View.GONE);
            }

            String s = f.getPath();

            List<String> ss = splitPathFilter(s);

            if (ss.size() == 0) {
                folder.setVisibility(View.GONE);
                file.setText("./" + s);
            } else {
                if (i == 0) {
                    File p1 = new File(makePath(ss)).getParentFile();
                    if (p1 != null) {
                        folder.setText("./" + p1.getPath());
                        folder.setVisibility(View.VISIBLE);
                    } else {
                        folder.setVisibility(View.GONE);
                    }
                } else {
                    File p1 = new File(makePath(ss)).getParentFile();
                    String s2 = getItem(i - 1).getPath();
                    List<String> ss2 = splitPathFilter(s2);
                    File p2 = new File(makePath(ss2)).getParentFile();
                    if (p1 == null || p1.equals(p2)) {
                        folder.setVisibility(View.GONE);
                    } else {
                        folder.setText("./" + p1.getPath());
                        folder.setVisibility(View.VISIBLE);
                    }
                }
                file.setText("./" + ss.get(ss.size() - 1));
            }

            return view;
        }
    }

    public static String makePath(List<String> ss) {
        if (ss.size() == 0)
            return "/";
        return TextUtils.join(File.separator, ss);
    }

    public List<String> splitPathFilter(String s) {
        List<String> ss = MainApplication.splitPath(s);
        if (ss.get(0).equals(torrentName))
            ss.remove(0);
        return ss;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        v = inflater.inflate(R.layout.torrent_player, container, false);

        final long t = getArguments().getLong("torrent");

        empty = v.findViewById(R.id.torrent_files_empty);

        final MainApplication app = ((MainApplication) getContext().getApplicationContext());

        next = v.findViewById(R.id.player_next);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player == null)
                    return; // not yet open, no metadata
                int i = player.getPlaying() + 1;
                if (i >= player.getSize())
                    i = 0;
                play(i);
                files.selected = -1;
                files.notifyDataSetChanged();
                playUpdate(true);
                list.smoothScrollToPosition(i);
            }
        });
        prev = v.findViewById(R.id.player_prev);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player == null)
                    return; // not yet open, no metadata
                int i = player.getPlaying();
                if (i == -1) {
                    i = 0;
                } else {
                    i = i - 1;
                }
                if (i < 0)
                    i = player.getSize() - 1;
                play(i);
                files.selected = -1;
                files.notifyDataSetChanged();
                playUpdate(true);
                list.smoothScrollToPosition(i);
            }
        });
        play = (ImageView) v.findViewById(R.id.player_play);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (app.player != null && app.player != player) {
                    boolean p = app.player.isPlaying();
                    if (p) { // if we point to unsupported file, open externaly
                        int index = files.selected;
                        if (index != -1) {
                            String type = files.getFileType(index);
                            if (!TorrentPlayer.isSupported(type)) {
                                Uri uri = files.getItem(index).uri;
                                openIntent(uri, type);
                                return;
                            }
                        } else {
                            app.player.pause();
                            return;
                        }
                    }
                    app.playerStop();
                    if (p) { // if were playing show play button; else start playing current
                        playUpdate(false);
                        return;
                    }
                } // no else
                if (player.getPlaying() == -1 && files.selected == -1) { // start playing with no selection
                    play(0);
                    playUpdate(true);
                } else if (player.isPlaying() || player.getPlaying() == files.selected || files.selected == -1) { // pause action
                    int i = player.getPlaying();
                    player.pause();
                    if (player.isStop()) { // we stoped 'next' loop, keep last item highligted
                        files.selected = i;
                        files.notifyDataSetChanged();
                        playUpdate(false);
                    } else {
                        if (player.isPlaying()) { // did we resume?
                            files.selected = -1; // clear user selection after resume
                            files.notifyDataSetChanged();
                        }
                        playUpdate(true);
                    }
                    MainApplication app = ((MainApplication) getContext().getApplicationContext());
                    TorrentPlayer.save(getContext(), app.player);
                } else { // play selected file
                    int index = files.selected;
                    String type = files.getFileType(index);
                    if (TorrentPlayer.isSupported(type)) {
                        play(index);
                        files.selected = -1;
                        files.notifyDataSetChanged();
                        playUpdate(true);
                    } else {
                        Uri uri = files.getItem(index).uri;
                        openIntent(uri, type);
                    }
                }
            }
        });
        seek = (SeekBar) v.findViewById(R.id.player_seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (app.player == null)
                        return;
                    app.player.seek(progress);
                    app.player.notifyProgress();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        playerPos = (TextView) v.findViewById(R.id.player_pos);
        playerDur = (TextView) v.findViewById(R.id.player_dur);

        download = v.findViewById(R.id.torrent_files_metadata);
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Libtorrent.downloadMetadata(t)) {
                    ((MainActivity) getActivity().getApplicationContext()).Error(Libtorrent.error());
                    return;
                }
            }
        });

        list = (ListView) v.findViewById(R.id.list);

        files = new Files();

        list.setAdapter(files);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                files.selected = position;
                files.notifyDataSetChanged();
                playUpdate();
            }
        });

        playerPos.setText(MainApplication.formatDuration(getContext(), 0));
        playerDur.setText(MainApplication.formatDuration(getContext(), 0));

        playerReceiver = new TorrentPlayer.Receiver(getContext()) {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (a.equals(TorrentPlayer.PLAYER_NEXT)) {
                    playUpdate(true);
                    files.notifyDataSetChanged();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (player != null && app.player == player) {
                                list.smoothScrollToPosition(player.getPlaying());
                            }
                        }
                    });
                }
                if (a.equals(TorrentPlayer.PLAYER_STOP)) {
                    playUpdate(false);
                    playerPos.setText(MainApplication.formatDuration(context, 0));
                    playerDur.setText(MainApplication.formatDuration(context, 0));
                    files.notifyDataSetChanged();
                }
                if (a.equals(TorrentPlayer.PLAYER_PROGRESS)) {
                    long pos = intent.getLongExtra("pos", 0);
                    long dur = intent.getLongExtra("dur", 0);
                    boolean p = intent.getBooleanExtra("play", false);
                    playerPos.setText(MainApplication.formatDuration(context, pos));
                    playerDur.setText(MainApplication.formatDuration(context, dur));
                    playUpdate(p);
                    seek.setMax((int) dur);
                    seek.setProgress((int) pos);
                }
            }
        };

        if (app.player != null)
            app.player.notifyProgress(playerReceiver);

        update();

        if (player != null) {
            int i = player.getPlaying() - 1;
            if (i < 0)
                i = 0;
            list.setSelection(i); // make 1 above
        }

        return v;
    }

    void openIntent(Uri uri, String type) {
        Intent intent = new Intent();
        intent.setDataAndType(uri, type);
        FileProvider.grantPermissions(getContext(), intent, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivity(intent);
    }

    public void openPlayer(long t) {
        MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (player != null) {
            if (player == app.player)
                app.player = null;
            player.close();
            player = null;
        }
        if (app.player != null) {
            if (app.player.getTorrent() == t) {
                player = app.player;
                player.notifyProgress();
                return;
            }
        }
        player = new TorrentPlayer(getContext(), app.getStorage(), t);
        updatePlayer();
    }

    public void play(int i) {
        MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (app.player != null) {
            if (app.player != player) {
                app.player.close();
            }
        }
        app.player = player;
        player.play(i);
    }

    @Override
    public void update() {
        long t = getArguments().getLong("torrent");

        if (Libtorrent.metaTorrent(t)) {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        }

        if (Libtorrent.metaTorrent(t)) {
            if (player == null || player.getTorrent() != t) {
                openPlayer(t);
            } else {
                long p = Libtorrent.torrentPendingBytesCompleted(t);
                long pp = Libtorrent.torrentPendingBytesLength(t);
                boolean d = pendindBytesUpdate != p; // downloading
                boolean u = pendindBytesLengthUpdate != pp; // user selected
                if (d || u) {
                    player.update();
                    pendindBytesUpdate = p;
                    pendindBytesLengthUpdate = pp;
                    if (u)
                        list.smoothScrollToPosition(player.getPlaying());
                }
            }
        }
        torrentName = Libtorrent.torrentName(t);

        files.notifyDataSetChanged();

        updatePlayer();
    }

    void updatePlayer() {
        int i = player != null ? View.VISIBLE : View.GONE;
        play.setVisibility(i);
        prev.setVisibility(i);
        next.setVisibility(i);
        seek.setVisibility(i);
        playerPos.setVisibility(i);
        playerDur.setVisibility(i);
    }

    @Override
    public void close() {
        if (playerReceiver != null) {
            playerReceiver.close();
            playerReceiver = null;
        }
        MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (player != null) {
            if (player == app.player) {
                ; // then it is playing. do nothing.
            } else {
                player.close();
            }
            player = null;
        }
    }

    void playUpdate() {
        boolean playing = false;
        final MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (app.player != null && app.player != player) {
            playing = app.player.getPlaying() != -1;
        } else if (player != null) {
            playing = player.getPlaying() != -1;
        }
        playUpdate(playing);
    }

    void playUpdate(boolean playing) {
        boolean dup = false;
        final MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (app.player != null && app.player != player) {
            dup = true;
        }
        if (!dup && playing) {
            play.setImageResource(R.drawable.ic_pause_24dp);
        } else {
            if (player != null) {
                int index = files.selected;
                if (index == -1) {
                    index = player.getPlaying();
                }
                if (index != -1) {
                    String type = files.getFileType(index);
                    if (TorrentPlayer.isSupported(type)) {
                        if (playing)
                            play.setImageResource(R.drawable.ic_pause_24dp);
                        else
                            play.setImageResource(R.drawable.play);
                    } else {
                        play.setImageResource(R.drawable.ic_open_in_new_black_24dp);
                    }
                } else {
                    if (playing)
                        play.setImageResource(R.drawable.ic_pause_24dp);
                    else
                        play.setImageResource(R.drawable.play);
                }
            } else {
                play.setImageResource(R.drawable.play);
            }
        }
    }
}
