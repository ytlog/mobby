package com.github.ytlog.mobby.android.device.privileged;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenTaskApiTest {
    public interface Legacy {
        void getTasks(int limit, boolean visibleOnly, boolean keepExtras);
    }
    public interface Scoped extends Legacy {
        void getTasks(int limit, boolean visibleOnly, boolean keepExtras, int displayId);
    }

    @Test public void choosesTheInstalledTaskSignature() throws Exception {
        assertEquals(3, VirtualScreen.resolveGetTasks(Legacy.class).getParameterCount());
        assertEquals(4, VirtualScreen.resolveGetTasks(Scoped.class).getParameterCount());
        assertThrows(NoSuchMethodException.class, () -> VirtualScreen.resolveGetTasks(Object.class));
    }
}
