package org.briarproject.briar.api.filetransfer;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class FileTransferProgress {

	public enum State {
		/** Transfer is in progress. */
		TRANSFERRING,
		/** All chunks have been delivered/received. */
		COMPLETE,
		/** Transfer was cancelled by the sender. */
		CANCELLED,
		/** Transfer was rejected by the receiver. */
		REJECTED,
		/** An error occurred. */
		ERROR
	}

	private final State state;
	private final long transferred;
	private final long total;

	public FileTransferProgress(State state, long transferred, long total) {
		this.state = state;
		this.transferred = transferred;
		this.total = total;
	}

	public State getState() {
		return state;
	}

	public long getTransferred() {
		return transferred;
	}

	public long getTotal() {
		return total;
	}

	/**
	 * Transferred bytes as a percentage of the total, clamped to [0, 100].
	 */
	public int getPercent() {
		if (total <= 0) return state == State.COMPLETE ? 100 : 0;
		int pct = (int) (transferred * 100 / total);
		if (pct < 0) return 0;
		if (pct > 100) return 100;
		return pct;
	}
}
