package com.pubbge.liharness.infrastructure.adapter.repository;

import com.pubbge.liharness.domain.settings.adapter.repository.ICredentialRepository;
import com.pubbge.liharness.infrastructure.support.yaml.YamlFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import com.pubbge.liharness.infrastructure.support.AtomicFiles;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link ICredentialRepository} 的 YAML 实现：读写 {@code ~/.liharness/.credentials.yaml}。
 *
 * <pre>
 * deepseek: sk-xxxxxxxx
 * my-gateway: gw-xxxxxxxx
 * </pre>
 *
 * <p>Key 是明文存在磁盘上的，所以：
 * <ul>
 *   <li>文件名以点开头（默认隐藏），只放 Key，不放别的配置；</li>
 *   <li>权限应该是 600。别人能读时在日志里警告（Windows 没有 POSIX 权限，跳过检查）；</li>
 *   <li>Key 只在调用模型时取出来，不进日志、不返回给前端。</li>
 * </ul>
 */
public final class YamlCredentialRepository implements ICredentialRepository {

    private static final System.Logger log = System.getLogger(YamlCredentialRepository.class.getName());

    private final YamlFile<Map<String, String>> file;
    private volatile boolean warned;

    public YamlCredentialRepository(Path credentialsFile) {
        this.file = new YamlFile<>(credentialsFile, YamlCredentialRepository::convert, Map.of());
    }

    @Override
    public Optional<String> find(String name) {
        Map<String, String> all;
        try {
            all = file.get();
        } catch (IllegalStateException e) {
            // 文件写坏了：当作没有保存的 Key（会退回到环境变量），记一条日志。注意消息里不能带文件内容
            log.log(System.Logger.Level.WARNING, "读取 " + file.path() + " 失败，忽略其中的 Key");
            return Optional.empty();
        }
        if (!all.isEmpty()) {
            warnIfReadableByOthers();
        }
        return Optional.ofNullable(all.get(name));
    }

    @Override
    public synchronized void save(String name, String apiKey) {
        Map<String, String> all = new TreeMap<>(readForWrite());
        all.put(name, apiKey.strip());
        write(all);
    }

    @Override
    public synchronized void remove(String name) {
        Map<String, String> all = new TreeMap<>(readForWrite());
        if (all.remove(name) != null) {
            write(all);
        }
    }

    @Override
    public Collection<String> values() {
        try {
            return List.copyOf(file.get().values());
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    /** 写之前读：文件写坏了就不要覆盖它，否则用户手写的其他 Key 就丢了 */
    private Map<String, String> readForWrite() {
        return file.get();
    }

    /** 整个文件重写（这个文件只放 Key，没有注释要保留），权限 600 */
    private void write(Map<String, String> all) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String text = "# LiHarness 保存的 API Key。只有你自己能读（权限 600），不要提交到 Git，不要发给别人\n"
            + (all.isEmpty() ? "" : new Yaml(options).dump(all));
        try {
            AtomicFiles.write(file.path(), text, true);
        } catch (IOException e) {
            throw new IllegalStateException("写入 " + file.path() + " 失败：" + e.getMessage(), e);
        }
    }

    static Map<String, String> convert(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null && !(v instanceof Map) && !(v instanceof Iterable)) {
                out.put(String.valueOf(k), String.valueOf(v).strip());
            }
        });
        return out;
    }

    private void warnIfReadableByOthers() {
        if (warned) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.path());
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                log.log(System.Logger.Level.WARNING, file.path() + " 其他用户也能读，建议执行 chmod 600 " + file.path());
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 等不支持 POSIX 权限的文件系统：跳过
        }
        warned = true;
    }
}
