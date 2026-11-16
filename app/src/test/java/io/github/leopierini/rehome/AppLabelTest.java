package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class AppLabelTest {

    @Test
    public void stripsFormattingAndBounds() {
        assertEquals("Netflix", AppLabel.clean("Netflix\u202E", "io.example"));
        assertEquals("a b", AppLabel.clean("a\n\nb", "io.example"));
        assertEquals("a b c", AppLabel.clean("  a \t b\u2028c  ", "io.example"));
        assertEquals("Zero width", AppLabel.clean("Zero\u200D width\u200B", "io.example"));
        assertEquals("Retro", AppLabel.clean("Retro", "io.example"));
        assertEquals("io.example", AppLabel.clean("", "io.example"));
        assertEquals("io.example", AppLabel.clean("\u202E\n\u0000", "io.example"));
        assertEquals("io.example", AppLabel.clean(null, "io.example"));
        char[] big = new char[100];
        Arrays.fill(big, 'x');
        assertEquals(40, AppLabel.clean(new String(big), "io.example").length());
        // 39 letters then an emoji (a surrogate pair): the pair does not fit in 40 and is left out whole.
        String emoji = new String(Character.toChars(0x1F3AE));
        assertEquals(39, AppLabel.clean(new String(big, 0, 39) + emoji + "y", "io.example").length());
        assertEquals(new String(big, 0, 38) + emoji, AppLabel.clean(new String(big, 0, 38) + emoji + "y", "io.example"));
    }
}
