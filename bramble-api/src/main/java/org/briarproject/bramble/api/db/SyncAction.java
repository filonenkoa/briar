package org.briarproject.bramble.api.db;

/**
 * A {@link CommitAction} that runs synchronously after commit.
 */
public class SyncAction implements CommitAction {

	private final Runnable task;

	SyncAction(Runnable task) {
		this.task = task;
	}

	public Runnable getTask() {
		return task;
	}

	@Override
	public void accept(Visitor visitor) {
		visitor.visit(this);
	}
}
