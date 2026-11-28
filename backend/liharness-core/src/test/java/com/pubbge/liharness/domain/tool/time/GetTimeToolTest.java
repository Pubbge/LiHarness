package com.pubbge.liharness.domain.tool.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pubbge.liharness.domain.spi.tool.ToolArgs;
import com.pubbge.liharness.domain.spi.tool.ToolException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GetTimeToolTest {

    /** 2026-11-25 02:00:00 UTC */
    private final GetTimeTool tool = new GetTimeTool(Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneId.of("UTC")));

    @Test
    void usesDefaultZone() throws Exception {
        assertEquals("2026-11-25 02:00:00 星期三（UTC，UTC+00:00）", tool.call(ToolArgs.empty(), null).content());
    }

    @Test
    void usesRequestedZone() throws Exception {
        ToolArgs args = new ToolArgs(Map.of("timezone", "Asia/Shanghai"), null);
        assertEquals("2026-11-25 10:00:00 星期三（Asia/Shanghai，UTC+08:00）", tool.call(args, null).content());
    }

    @Test
    void unknownZoneIsToolError() {
        ToolArgs args = new ToolArgs(Map.of("timezone", "Mars/Olympus"), null);
        assertThrows(ToolException.class, () -> tool.call(args, null));
    }
}
