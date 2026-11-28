package com.pubbge.liharness.domain.tool.time;

import com.pubbge.liharness.domain.spi.tool.Tool;
import com.pubbge.liharness.domain.spi.tool.ToolArgs;
import com.pubbge.liharness.domain.spi.tool.ToolContext;
import com.pubbge.liharness.domain.spi.tool.ToolException;
import com.pubbge.liharness.domain.spi.tool.ToolResult;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** 获取当前时间。模型自己不知道“现在”是什么时候，这是最简单也最常用的工具 */
public final class GetTimeTool implements Tool {

    public static final String NAME = "get_time";

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Clock clock;

    public GetTimeTool() {
        this(Clock.systemDefaultZone());
    }

    /** 测试时传入固定时钟，结果可预测 */
    public GetTimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "获取当前的日期和时间（含星期和时区）。用户问到“现在”“今天”“几点”，"
            + "或者需要根据当前日期计算时，先调用这个工具，不要凭感觉猜。";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "timezone": {
                  "type": "string",
                  "description": "IANA 时区名，例如 Asia/Shanghai、America/New_York。不填则使用本机时区"
                }
              }
            }""";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolResult call(ToolArgs args, ToolContext context) throws ToolException {
        ZoneId zone = clock.getZone();
        String tz = args.string("timezone", "").trim();
        if (!tz.isEmpty()) {
            try {
                zone = ZoneId.of(tz);
            } catch (DateTimeException e) {
                throw new ToolException("不认识的时区：" + tz + "。请使用 IANA 时区名，例如 Asia/Shanghai");
            }
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        String offset = now.getOffset().getId();
        if ("Z".equals(offset)) {
            offset = "+00:00"; // UTC 本身的偏移量 id 是 "Z"，统一写成 +00:00 更好读
        }
        return ToolResult.ok(now.format(FORMAT) + " " + weekday + "（" + zone.getId() + "，UTC" + offset + "）");
    }
}
