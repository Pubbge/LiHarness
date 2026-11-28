package com.pubbge.liharness.domain.settings.adapter.repository;

import com.pubbge.liharness.domain.settings.model.valobj.Settings;

import java.util.function.UnaryOperator;

/**
 * 读写用户设置（{@code ~/.liharness/settings.yaml}）。
 *
 * <p>实现要做到<b>改了文件不用重启</b>：每次调用都返回文件当前的内容（可以按修改时间缓存）。
 * 设置页保存和用编辑器改文件，改的是同一个文件。
 */
public interface ISettingsRepository {

    /**
     * @return 当前设置；文件不存在时返回 {@link Settings#empty()}
     * @throws IllegalStateException 文件存在但写错了（YAML 语法错误、字段类型不对），消息里说明哪里错了
     */
    Settings current();

    /**
     * 读出当前设置、按 change 修改、写回去，三步是一个整体（不会被另一次修改插进来）。
     *
     * <p>为什么传一个函数，而不是直接传新设置：如果调用方先 current() 再 save(新设置)，两个请求同时改，
     * 后写的会把先写的覆盖掉。
     *
     * @return 写入后的设置
     * @throws IllegalArgumentException 改完的设置不合法，文件保持原样
     */
    Settings update(UnaryOperator<Settings> change);
}
