package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InputActionTest {
    @Test public void anActivityIsAComponentAndAnActionIsNot() {
        assertTrue(InputAction.isComponent("com.xiaomi.mitv.tvplayer/.ExternalSourceActivity"));
        assertTrue(InputAction.isComponent("com.xiaomi.mitv.tvplayer/com.xiaomi.mitv.tvplayer.ExternalSourceActivity"));
        assertFalse(InputAction.isComponent("com.xgimi.action.hdmiPlayer"));
        assertFalse(InputAction.isComponent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY"));
        assertFalse(InputAction.isComponent(""));
        assertFalse(InputAction.isComponent(null));
        assertFalse(InputAction.isComponent("/.Activity"));
        assertFalse(InputAction.isComponent("com.example/"));
        assertFalse(InputAction.isComponent("a/b/c"));
    }
}
