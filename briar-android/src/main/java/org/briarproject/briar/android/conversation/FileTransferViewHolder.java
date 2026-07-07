package org.briarproject.briar.android.conversation;

import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.api.filetransfer.FileTransferProgress;
import org.briarproject.briar.api.filetransfer.FileTransferProgress.State;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.util.UiUtils.formatFileSize;

@NotNullByDefault
class FileTransferViewHolder extends ConversationItemViewHolder {

	private final ImageView fileIcon;
	private final TextView fileName;
	private final TextView fileSize;
	private final ProgressBar progressBar;
	private final TextView progressText;
	private final LifecycleOwner lifecycleOwner;

	@Nullable
	private LiveData<FileTransferProgress> progressLiveData = null;
	@Nullable
	private Observer<FileTransferProgress> progressObserver = null;

	FileTransferViewHolder(View v, ConversationListener listener,
			LifecycleOwner lifecycleOwner,
			boolean isIncoming) {
		super(v, listener, isIncoming);
		this.lifecycleOwner = lifecycleOwner;
		fileIcon = v.findViewById(R.id.fileIcon);
		fileName = v.findViewById(R.id.fileName);
		fileSize = v.findViewById(R.id.fileSize);
		progressBar = v.findViewById(R.id.progressBar);
		progressText = v.findViewById(R.id.progressText);
	}

	@Override
	void bind(ConversationItem conversationItem, boolean selected) {
		unbind();
		super.bind(conversationItem, selected);
		ConversationFileItem item = (ConversationFileItem) conversationItem;

		fileName.setText(item.getHeader().getFileName());
		fileSize.setText(formatFileSize(itemView.getContext(),
				item.getHeader().getFileSize()));
		itemView.setOnClickListener(view -> listener.onFileClicked(item));

		LiveData<FileTransferProgress> liveData = item.getProgress();
		Observer<FileTransferProgress> observer = this::bindProgress;
		progressLiveData = liveData;
		progressObserver = observer;
		bindProgress(liveData.getValue());
		liveData.observe(lifecycleOwner, observer);
	}

	void unbind() {
		if (progressLiveData != null && progressObserver != null) {
			progressLiveData.removeObserver(progressObserver);
		}
		progressLiveData = null;
		progressObserver = null;
	}

	private void bindProgress(@Nullable FileTransferProgress p) {
		if (p == null) return;
		int pct = p.getPercent();
		State state = p.getState();
		if (state == State.COMPLETE) {
			progressBar.setVisibility(GONE);
			progressText.setText(R.string.file_transfer_tap_to_open);
		} else if (state == State.ERROR) {
			progressBar.setVisibility(GONE);
			progressText.setText(R.string.file_transfer_error);
		} else {
			progressBar.setVisibility(VISIBLE);
			progressBar.setProgress(pct);
			progressText.setText(itemView.getContext().getString(
					R.string.file_transfer_progress, pct,
					formatFileSize(itemView.getContext(), p.getTransferred()),
					formatFileSize(itemView.getContext(), p.getTotal())));
		}
	}
}
