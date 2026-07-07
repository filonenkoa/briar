package org.briarproject.briar.android.conversation;

import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.briarproject.briar.api.filetransfer.FileTransferProgress;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.briarproject.briar.api.filetransfer.FileTransferProgress.State.TRANSFERRING;

@NotNullByDefault
class ConversationFileItem extends ConversationItem {

	private final FileTransferHeader header;
	private final MutableLiveData<FileTransferProgress> internalProgress;
	private LiveData<FileTransferProgress> progress;

	ConversationFileItem(@LayoutRes int layoutRes, FileTransferHeader h,
			LiveData<String> contactName) {
		super(layoutRes, h, contactName);
		this.header = h;
		this.internalProgress = new MutableLiveData<>(new FileTransferProgress(
				TRANSFERRING, 0, h.getFileSize()));
		this.progress = internalProgress;
	}

	FileTransferHeader getHeader() {
		return header;
	}

	LiveData<FileTransferProgress> getProgress() {
		return progress;
	}

	/**
	 * Replaces the progress {@link LiveData} with one provided by the
	 * {@link ConversationViewModel}, which is periodically polled.
	 */
	void setProgressLiveData(LiveData<FileTransferProgress> liveData) {
		this.progress = liveData;
	}

	void setProgress(FileTransferProgress p) {
		internalProgress.postValue(p);
	}
}
