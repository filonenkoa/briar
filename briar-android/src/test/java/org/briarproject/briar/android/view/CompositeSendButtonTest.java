package org.briarproject.briar.android.view;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class CompositeSendButtonTest {

	@Test
	public void exposesSeparateFileClickListener() throws Exception {
		Method method = CompositeSendButton.class.getMethod(
				"setOnFileClickListener", View.OnClickListener.class);

		assertNotNull(method);
	}
}
