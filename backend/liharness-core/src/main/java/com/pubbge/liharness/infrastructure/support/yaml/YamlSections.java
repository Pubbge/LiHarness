package com.pubbge.liharness.infrastructure.support.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 只替换 YAML 文件里的某一个顶层段落，其余内容（包括注释、空行、顺序）原样保留。
 *
 * <p>为什么不整个重写：settings.yaml 是给人手写的，用户会在里面写注释、按自己的习惯排顺序。
 * 设置页切换一下模型，就把整个文件重新生成一遍、把注释全丢掉，体验很差。
 * 所以只把要改的那一段换掉：
 *
 * <pre>
 * # 我的设置                     ← 保留
 * defaultModel: mock/mock       ← 只替换这一段
 * providers:                    ← 保留
 *   my-gateway: ...
 * </pre>
 *
 * <p>“一段”是指从 {@code key:} 这一行开始，到下一个顶层的 key（不缩进、不是注释的行）之前。
 * 被替换的段落里面的注释会丢掉，这是这个简单做法的代价。
 */
public final class YamlSections {

    private YamlSections() {
    }

    /**
     * @param text  文件原来的内容
     * @param key   顶层的 key
     * @param value 新的值（String、Number、Boolean、List、Map）；null 表示删除这一段
     * @return 新的内容
     */
    public static String replace(String text, String key, Object value) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // 去掉末尾换行产生的空串，最后再统一加上
        }
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isTopLevelKey(lines.get(i), key)) {
                start = i;
                break;
            }
        }
        List<String> section = value == null ? List.of() : dump(key, value);
        if (start < 0) {
            lines.addAll(section); // 原来没有这一段：加在最后
        } else {
            int end = start + 1;
            while (end < lines.size() && !startsTopLevel(lines.get(end))) {
                end++;
            }
            // 段落末尾的空行和注释属于下一段（通常是写给下一段的说明），留着
            while (end > start + 1 && isBlankOrComment(lines.get(end - 1))) {
                end--;
            }
            lines.subList(start, end).clear();
            lines.addAll(start, section);
        }
        return String.join("\n", lines) + "\n";
    }

    private static List<String> dump(String key, Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); // 多行写法，和手写的一样
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setAllowUnicode(true); // 中文原样写出，不转成 \\u 转义
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        String out = new Yaml(options).dump(m);
        return List.of(out.stripTrailing().split("\n"));
    }

    private static boolean isTopLevelKey(String line, String key) {
        return line.startsWith(key + ":") || line.startsWith("\"" + key + "\":") || line.startsWith("'" + key + "':");
    }

    /** 新的一个顶层段落：不缩进、不是注释、不是空行、也不是多行字符串的延续 */
    private static boolean startsTopLevel(String line) {
        return !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")
            && !line.startsWith("-") && !line.startsWith("...");
    }

    private static boolean isBlankOrComment(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("#");
    }
}
