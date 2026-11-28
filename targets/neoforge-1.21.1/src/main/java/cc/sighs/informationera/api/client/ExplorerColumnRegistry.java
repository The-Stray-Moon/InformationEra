package cc.sighs.informationera.api.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 注册列时使用的收集器。每个 provider 会拿到独立实例，注册失败时整批丢弃。
 */
public final class ExplorerColumnRegistry {

    private final List<ExplorerColumn> columns = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final List<ExplorerGroup> groups = new ArrayList<>();
    private final Set<String> groupIds = new LinkedHashSet<>();

    public ExplorerColumnRegistry() {
    }

    /**
     * 注册一列。ID 在同一个 provider 内必须唯一；跨 provider 的冲突由宿主记录并跳过后来者。
     *
     * <p>列声明了分组规则时，列 ID 同时占用一个分组依据 ID，不能再注册同 ID 的独立依据。
     */
    public void register(ExplorerColumn column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        if (!ids.add(column.id())) {
            throw new IllegalArgumentException("duplicate column id: " + column.id());
        }
        if (column.groupRule() != null && !groupIds.add(column.id())) {
            throw new IllegalArgumentException("duplicate group id: " + column.id());
        }
        columns.add(column);
    }

    /**
     * 注册一个没有详情列的分组依据。ID 在同一个 provider 内必须唯一。
     */
    public void registerGroup(ExplorerGroup group) {
        if (group == null) {
            throw new IllegalArgumentException("group must not be null");
        }
        if (!groupIds.add(group.id())) {
            throw new IllegalArgumentException("duplicate group id: " + group.id());
        }
        groups.add(group);
    }

    /** 已注册列，按 provider 注册顺序。 */
    public List<ExplorerColumn> columns() {
        return Collections.unmodifiableList(new ArrayList<>(columns));
    }

    /** 已注册的独立分组依据，按 provider 注册顺序。 */
    public List<ExplorerGroup> groups() {
        return Collections.unmodifiableList(new ArrayList<>(groups));
    }
}
