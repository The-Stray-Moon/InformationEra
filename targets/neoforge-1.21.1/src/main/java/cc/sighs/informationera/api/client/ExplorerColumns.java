package cc.sighs.informationera.api.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 {@link ExplorerColumnProvider} 实现类，由 Information Era 在客户端启动时自动发现。
 *
 * <p>只扫描客户端模组类；provider 必须有 public 无参构造器。发现失败或 provider 抛异常时
 * 只影响该 provider 自己，不会阻止其它列注册或资源管理器打开。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExplorerColumns {
}
