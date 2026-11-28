package com.pubbge.liharness.domain.spi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgsTest {

    @Test
    void readsTypedValues() throws Exception {
        ToolArgs args = new ToolArgs(Map.of("path", "a.txt", "limit", 20, "all", true, "tags", List.of("x", "y")), null);
        assertEquals("a.txt", args.string("path"));
        assertEquals(20, args.integer("limit", 0));
        assertTrue(args.bool("all", false));
        assertEquals(List.of("x", "y"), args.stringList("tags"));
    }

    @Test
    void defaultsWhenMissing() throws Exception {
        ToolArgs args = ToolArgs.empty();
        assertEquals("d", args.string("x", "d"));
        assertEquals(7, args.integer("n", 7));
        assertEquals(List.of(), args.stringList("tags"));
    }

    @Test
    void toleratesNumbersWrittenAsStringsOrDoubles() throws Exception {
        assertEquals(10, new ToolArgs(Map.of("n", "10"), null).integer("n", 0));
        assertEquals(3, new ToolArgs(Map.of("n", 3.0), null).integer("n", 0));
        assertThrows(ToolException.class, () -> new ToolArgs(Map.of("n", 3.5), null).integer("n", 0));
    }

    @Test
    void wrongTypeOrMissingRequiredIsToolException() {
        ToolArgs args = new ToolArgs(Map.of("path", 42), null);
        assertThrows(ToolException.class, () -> args.string("path"));
        assertThrows(ToolException.class, () -> ToolArgs.empty().string("path"));
    }
}
