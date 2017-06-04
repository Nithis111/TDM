package com.github.axet.torrentclient.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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

import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.torrentclient.app.TorrentPlayer;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.Comparator;
import java.util.List;

import libtorrent.Libtorrent;

public class PlayerFragment extends Fragment implements MainActivity.TorrentFragmentInterface {
    View v;
    ListView list;
    View download;
    View empty;
    Files files;
    String torrentName;
    TorrentPlayer player;
    TorrentPlayer.Receiver playerReceiver;
    ImageView play;
    TextView playerPos;
    TextView playerDur;
    SeekBar seek;
    Handler handler = new Handler();

    static class SortFiles implements Comparator<TorrentPlayer.PlayerFile> {
        @Override
        public int compare(TorrentPlayer.PlayerFile file, TorrentPlayer.PlayerFile file2) {
            List<String> s1 = MainApplication.splitPath(file.getPath());
            List<String> s2 = MainApplication.splitPath(file2.getPath());

            int c = new Integer(s1.size()).compareTo(s2.size());
            if (c != 0)
                return c;

            for (int i = 0; i < s1.size(); i++) {
                String p1 = s1.get(i);
                String p2 = s2.get(i);
                c = p1.compareTo(p2);
                if (c != 0)
                    return c;
            }

            return 0;
        }
    }

    public class Files extends BaseAdapter {
        public int selected = -1;

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
                    folder.setVisibility(View.GONE);
                } else {
                    File p1 = new File(makePath(ss)).getParentFile();
                    File p2 = new File(makePath(splitPathFilter(getItem(i - 1).getPath()))).getParentFile();
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

        View next = v.findViewById(R.id.player_next);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int i = player.getPlaying() + 1;
                if (i >= player.getSize())
                    i = 0;
                play(i);
                files.notifyDataSetChanged();
            }
        });
        View prev = v.findViewById(R.id.player_prev);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int i = player.getPlaying() - 1;
                if (i < 0)
                    i = player.getSize() - 1;
                play(i);
                files.notifyDataSetChanged();
            }
        });
        play = (ImageView) v.findViewById(R.id.player_play);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player.isPlaying() || player.getPlaying() == files.selected || files.selected == -1) {
                    player.pause();
                    MainApplication app = ((MainApplication) getContext().getApplicationContext());
                    app.playerSave();
                } else {
                    play(files.selected);
                }
            }
        });
        seek = (SeekBar) v.findViewById(R.id.player_seek);
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
            }
        });

        playerReceiver = new TorrentPlayer.Receiver(getContext()) {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (a.equals(TorrentPlayer.PLAYER_NEXT)) {
                    files.notifyDataSetChanged();
                    postScroll();
                }
                if (a.equals(TorrentPlayer.PLAYER_PROGRESS)) {
                    int pos = intent.getIntExtra("pos", 0);
                    int dur = intent.getIntExtra("dur", 0);
                    boolean p = intent.getBooleanExtra("play", false);
                    playerPos.setText(MainApplication.formatDuration(context, pos));
                    playerDur.setText(MainApplication.formatDuration(context, dur));
                    if (p)
                        play.setImageResource(R.drawable.ic_pause_24dp);
                    else
                        play.setImageResource(R.drawable.play);
                    seek.setMax(dur);
                    seek.setProgress(pos);
                }
            }
        };

        openPlayer(t);
        update();
        list.setSelection(player.getPlaying());

        return v;
    }

    public void openPlayer(long t) {
        MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (player != null) {
            if (player.getTorrent() == t)
                return;
            player.close();
        }
        if (app.player != null) {
            if (app.player.getTorrent() == t) {
                player = app.player;
                player.notifyPlayer();
                return;
            }
        }
        player = new TorrentPlayer(getContext(), t);
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

        empty.setVisibility(Libtorrent.metaTorrent(t) ? View.GONE : View.VISIBLE);

        torrentName = Libtorrent.torrentName(t);

        files.notifyDataSetChanged();
    }

    @Override
    public void close() {
        MainApplication app = ((MainApplication) getContext().getApplicationContext());
        if (player != null && app.player != null) {
            if (app.player != player) {
                player.close();
            }
            player = null;
        }
       playerReceiver.close();
    }

    void postScroll() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (player != null)
                    list.smoothScrollToPosition(player.getPlaying());
            }
        });
    }
}