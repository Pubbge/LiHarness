package com.pubbge.liharness.domain.settings.adapter.repository;

import java.util.Collection;
import java.util.Optional;

/**
 * 保存的 API Key（{@code ~/.liharness/.credentials.yaml}，权限 600）。
 *
 * <p>和 settings.yaml 分成两个文件：settings.yaml 可以放心给别人看，这个文件只放 Key 本身。
 * 名字就是服务商的 id。没有任何接口会把 Key 返回给前端。
 */
public interface ICredentialRepository {

    /** @param name 服务商的 id */
    Optional<String> find(String name);

    /** 保存。已有同名的就覆盖 */
    void save(String name, String apiKey);

    /** 删除。没有这个名字也不报错 */
    void remove(String name);

    /** 所有保存的 Key 的值：只用来从日志、工具输出里抹掉它们，不能返回给前端 */
    Collection<String> values();
}
