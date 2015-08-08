/*
 * This file is part of Popcorn Time.
 *
 * Popcorn Time is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Popcorn Time is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Popcorn Time. If not, see <http://www.gnu.org/licenses/>.
 */

package pct.droid.base.torrent;

import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.Priority;
import com.frostwire.jlibtorrent.TorrentAlertAdapter;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.BlockFinishedAlert;
import com.frostwire.jlibtorrent.alerts.PeerConnectAlert;
import com.frostwire.jlibtorrent.alerts.PeerDisconnectedAlert;
import com.frostwire.jlibtorrent.alerts.PieceFinishedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentAlert;
import com.frostwire.jlibtorrent.swig.int_vector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import pct.droid.base.PopcornApplication;
import timber.log.Timber;

public class Torrent extends TorrentAlertAdapter {

    private final static Integer MAX_PREPARE_COUNT = 20;
    private final static Integer MIN_PREPARE_COUNT = 2;
    private final static Integer DEFAULT_PREPARE_COUNT = 5;
    private final static Long PREPARE_SIZE = 10 * 1024L * 1024L;

    public enum State { UNKNOWN, RETRIEVING_META, PREPARING, STREAMING }

    private int mPiecesToPrepare;
    private int mLastPieceIndex;
    private int mFirstPieceIndex;
    private int mSelectedFile = -1;

    private Double mPrepareProgress = 0d;
    private Double mProgressStep = 0d;
    private boolean mReady = false;
    private List<Integer> mPieceIndices;
    private Listener mListener;

    private State mState = State.RETRIEVING_META;

    /**
     * The constructor for a new Torrent
     *
     * First the largest file in the download is selected as the file for playback TODO: make other file selection possible using dialog
     *
     * After setting this priority, the first and last index of the pieces that make up this file are determined.
     * And last: amount of pieces that are needed for playback are calculated (needed for playback means: make up 10 megabyte of the file)
     *
     * @param torrentHandle {@link TorrentHandle}
     */
    public Torrent(TorrentHandle torrentHandle) {
        super(torrentHandle);

        torrentHandle.setPriority(Priority.SEVEN.getSwig());

        if(mSelectedFile == -1)
            setSelectedFile(mSelectedFile);

        if(mListener != null)
            mListener.onStreamStarted();
    }

    /**
     * Reset piece priorities
     * First set all piece priorities to {@link Priority}.NORMAL and then set the file priority to the file selected for playback.
     */
    private void resetPriorities() {
        Priority[] priorities = getPiecePriorities();
        for (int i = 0; i < priorities.length; i++) {
            th.setPiecePriority(i, Priority.IGNORE);
        }

        for (int i = 0; i < th.getTorrentInfo().getNumFiles(); i++) {
            if(i == mSelectedFile) {
                th.setFilePriority(i, Priority.SEVEN);
            } else {
                th.setFilePriority(i, Priority.IGNORE);
            }
        }
    }

    public TorrentHandle getTorrentHandle() {
        return th;
    }

    /**
     * Get torrent piece priorities
     * @return Piece priorities
     */
    public Priority[] getPiecePriorities() {
        Priority[] piece_priorities = th.getPiecePriorities();
        Priority[] priorities = new Priority[piece_priorities.length];
        for (int i = 0; i < priorities.length; i++) {
            priorities[i] = piece_priorities[i];
        }
        return priorities;
    }

    public File getVideoFile() {
        return new File(PopcornApplication.getStreamDir(), th.getTorrentInfo().getFiles().getFilePath(mSelectedFile));
    }

    public File getSaveLocation() {
        return new File(th.getSavePath() + "/" + th.getName());
    }

    public void resume() {
        th.resume();
    }

    public void pause() {
        th.pause();
    }

    public void setSelectedFile(int selectedFileIndex) {
        TorrentInfo torrentInfo = th.getTorrentInfo();
        FileStorage fileStorage = torrentInfo.getFiles();
        Timber.d(th.getSavePath());
        Timber.d(th.getName());

        if(selectedFileIndex == -1) {
            long highestFileSize = 0;
            int selectedFile = -1;
            for (int i = 0; i < fileStorage.getNumFiles(); i++) {
                long fileSize = fileStorage.getFileSize(i);
                if (highestFileSize < fileSize) {
                    highestFileSize = fileSize;
                    th.setFilePriority(selectedFile, Priority.IGNORE);
                    selectedFile = i;
                    th.setFilePriority(i, Priority.SEVEN);
                } else {
                    th.setFilePriority(i, Priority.IGNORE);
                }
            }
            selectedFileIndex = selectedFile;
        } else {
            for (int i = 0; i < fileStorage.getNumFiles(); i++) {
                if(i == selectedFileIndex) {
                    th.setFilePriority(i, Priority.SEVEN);
                } else {
                    th.setFilePriority(i, Priority.IGNORE);
                }
            }
        }
        mSelectedFile = selectedFileIndex;

        Priority[] piecePriorities = getPiecePriorities();
        int firstPieceIndex = -1;
        int lastPieceIndex = -1;
        for (int i = 0; i < piecePriorities.length; i++) {
            if (piecePriorities[i] != Priority.IGNORE) {
                if (firstPieceIndex == -1) {
                    firstPieceIndex = i;
                }
                piecePriorities[i] = Priority.IGNORE;
            } else {
                if (firstPieceIndex != -1 && lastPieceIndex == -1) {
                    lastPieceIndex = i - 1;
                }
            }
        }

        if (lastPieceIndex == -1) {
            lastPieceIndex = piecePriorities.length - 1;
        }
        int pieceCount = lastPieceIndex - firstPieceIndex + 1;
        int pieceLength = th.getTorrentInfo().getPieceLength();
        int activePieceCount;
        if (pieceLength > 0) {
            activePieceCount = (int) (PREPARE_SIZE / pieceLength);
            if (activePieceCount < MIN_PREPARE_COUNT) {
                activePieceCount = MIN_PREPARE_COUNT;
            } else if (activePieceCount > MAX_PREPARE_COUNT) {
                activePieceCount = MAX_PREPARE_COUNT;
            }
        } else {
            activePieceCount = DEFAULT_PREPARE_COUNT;
        }

        if (pieceCount < activePieceCount) {
            activePieceCount = pieceCount / 2;
        }

        mFirstPieceIndex = firstPieceIndex;
        mLastPieceIndex = lastPieceIndex;
        mPiecesToPrepare = activePieceCount;
    }

    /**
     * Prepare torrent for playback. Prioritize the first `mPiecesToPrepare` pieces and the last `mPiecesToPrepare` pieces
     * from `mFirstPieceIndex` and `mLastPieceIndex`. Ignore all other pieces.
     */
    public void prepareTorrent() {
        if(mState == State.STREAMING) return;
        mState = State.PREPARING;

        List<Integer> indices = new ArrayList<>();

        Priority[] priorities = getPiecePriorities();
        for (int i = 0; i < priorities.length; i++) {
            if(priorities[i] != Priority.IGNORE) {
                th.setPiecePriority(i, Priority.IGNORE);
            }
        }

        for (int i = 0; i < mPiecesToPrepare; i++) {
            indices.add(mLastPieceIndex - i);
            th.setPiecePriority(mLastPieceIndex - i, Priority.SEVEN);
        }

        for (int i = 0; i < mPiecesToPrepare; i++) {
            indices.add(mFirstPieceIndex + i);
            th.setPiecePriority(mFirstPieceIndex + i, Priority.SEVEN);
        }

        mPieceIndices = indices;

        double blockCount = 0;
        for(Integer index : indices) {
            blockCount += (int) Math.ceil(th.getTorrentInfo().getPieceSize(index) / th.getStatus().getBlockSize());
        }

        mProgressStep = 100 / blockCount;

        th.resume();

        mListener.onStreamStarted();
    }

    /**
     * Start sequential download mode. First reset piece priorities.
     */
    public void startSequentialMode() {
        resetPriorities();

        th.setAutoManaged(true);
        th.setSequentialDownload(true);
    }

    public State getState() {
        return mState;
    }

    @Override
    public void pieceFinished(PieceFinishedAlert alert) {
        super.pieceFinished(alert);
        Timber.d(alert.getMessage());

        for(Integer index : mPieceIndices) {
            if(index == alert.getPieceIndex())
                mPieceIndices.remove(index);
        }

        if(mPieceIndices.size() == 0 && !mReady) {
            startSequentialMode();

            mReady = true;
            Timber.d("onStreamReady");
            mState = State.STREAMING;

            if(mListener != null)
                mListener.onStreamReady(getVideoFile());
        }
    }

    public void blockFinished(BlockFinishedAlert alert) {
        super.blockFinished(alert);

        for(Integer index : mPieceIndices) {
            if(index == alert.getPieceIndex()) {
                mPrepareProgress += mProgressStep;
            }
        }

        sendStreamProgress();
    }

    @Override
    public void peerConnect(PeerConnectAlert alert) {
        super.peerConnect(alert);
        sendStreamProgress();
    }

    @Override
    public void peerDisconnected(PeerDisconnectedAlert alert) {
        super.peerDisconnected(alert);
        sendStreamProgress();
    }

    private void sendStreamProgress() {
        TorrentStatus status = th.getStatus();
        float progress = status.getProgress() * 100;
        int seeds = status.getNumSeeds();
        int downloadSpeed = status.getDownloadPayloadRate();

        if(mListener != null && mPrepareProgress >= 1)
            mListener.onStreamProgress(new DownloadStatus(progress, mPrepareProgress.intValue(), seeds, downloadSpeed));
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public interface Listener {
        void onStreamStarted();

        void onStreamError(Exception e);

        void onStreamReady(File videoFile);

        void onStreamProgress(DownloadStatus status);
    }

}
