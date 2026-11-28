package com.pubbge.liharness.infrastructure.support.yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 一个“改了就生效”的 YAML 文件：每次 {@link #get()} 都看一眼文件的修改时间，变了才重新解析。
 *
 * <p>为什么要缓存：模型注册表每个回合都要读设置。直接每次解析也不慢，但没必要；按修改时间 + 大小判断是否变化，
 * 几乎没有开销，又能保证用户在编辑器里保存以后，下一次请求就用新内容。
 *
 * <p>为什么用 SafeConstructor：普通的 {@code new Yaml()} 能按 YAML 里的标签（{@code !!javax.script...}）
 * 创建任意 Java 对象，这是有名的反序列化漏洞。配置文件只需要 Map、List、字符串、数字、布尔，SafeConstructor 只认这些。
 *
 * @param <T> 解析后的类型
 */
public final class YamlFile<T> {

    private record Stamp(FileTime modified, long size) {
    }

    private final Path file;
    private final Function<Map<String, Object>, T> convert;
    private final T whenMissing;

    private Stamp stamp;
    private T value;

    /**
     * @param convert     把解析出来的 Map 转成需要的类型；内容不对时抛 IllegalArgumentException
     * @param whenMissing 文件不存在时返回的值
     */
    public YamlFile(Path file, Function<Map<String, Object>, T> convert, T whenMissing) {
        this.file = file;
        this.convert = convert;
        this.whenMissing = whenMissing;
    }

    public Path path() {
        return file;
    }

    /**
     * @throws IllegalStateException 文件存在但读不了、不是合法的 YAML，或者内容不对。消息里带着文件路径
     */
    public synchronized T get() {
        Stamp now;
        try {
            now = new Stamp(Files.getLastModifiedTime(file), Files.size(file));
        } catch (NoSuchFileException e) {
            stamp = null;
            value = null;
            return whenMissing;
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
        if (!now.equals(stamp)) {
            value = parse();
            stamp = now;
        }
        return value;
    }

    private T parse() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
        Map<String, Object> map;
        try {
            map = parseMap(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(file + " " + e.getMessage(), e);
        }
        if (map == null) {
            return whenMissing; // 空文件
        }
        try {
            return Objects.requireNonNull(convert.apply(map));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(file + " 写错了：" + e.getMessage(), e);
        }
    }

    /**
     * 安全地解析一段 YAML（只认 Map、List、字符串、数字、布尔）。
     *
     * @return 最外层的键值对；内容为空返回 null
     * @throws IllegalArgumentException 不是合法的 YAML，或者最外层不是键值对
     */
    public static Map<String, Object> parseMap(String text) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("不是合法的 YAML：" + e.getMessage(), e);
        }
        if (root == null) {
            return null;
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("的最外层要是键值对（key: value）");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) map;
        return m;
    }
}
