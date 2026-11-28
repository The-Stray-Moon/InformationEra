package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumn;
import cc.sighs.informationera.api.client.ExplorerColumnContext;
import cc.sighs.informationera.api.client.ExplorerColumnProvider;
import cc.sighs.informationera.api.client.ExplorerColumns;
import cc.sighs.informationera.api.client.ExplorerColumnRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 内置列 + 第三方列注册表，以及所有列取值/排序/标签的异常隔离入口。
 *
 * <p>只应由客户端启动流程调用 {@link #discover()}；发现使用 {@link ModFileScanData}，
 * 不主动加载服务端类。第三方列 ID 进入 {@link ExplorerViewState} 的持久化键，增删模组
 * 不会改变其它列的设置归属。
 */
public final class ExplorerColumnService {

    private static final Logger LOGGER = LoggerFactory.getLogger("informationera/columns");
    private static final Set<String> REPORTED_FAILURES = new HashSet<>();

    private static final List<ExplorerColumn> COLUMNS = new ArrayList<>();
    private static final Map<String, ExplorerColumn> BY_ID = new HashMap<>();
    private static boolean discovered;

    static {
        for (ExplorerColumn column : builtInColumns()) {
            COLUMNS.add(column);
            BY_ID.put(column.id(), column);
        }
    }

    private ExplorerColumnService() {
    }

    /** 客户端启动时调用一次：扫描所有 mod 的 {@link ExplorerColumns} provider。 */
    public static void discover() {
        if (discovered) {
            return;
        }
        discovered = true;
        Set<String> visited = new HashSet<>();
        List<ExplorerColumn> external = new ArrayList<>();
        try {
            for (ModFileScanData scan : ModList.get().getAllScanData()) {
                scan.getAnnotatedBy(ExplorerColumns.class, ElementType.TYPE).forEach(data -> {
                    String className = data.clazz().getClassName();
                    if (!visited.add(className)) {
                        return;
                    }
                    registerProviderClass(className, external);
                });
            }
        } catch (Throwable failure) {
            LOGGER.warn("Could not scan for explorer column providers", failure);
        }
        external.sort(Comparator.comparing(ExplorerColumn::id));
        for (ExplorerColumn column : external) {
            if (BY_ID.containsKey(column.id())) {
                LOGGER.warn("Skipping duplicate explorer column id '{}' from an external mod", column.id());
                continue;
            }
            COLUMNS.add(column);
            BY_ID.put(column.id(), column);
        }
        LOGGER.info("Explorer columns ready: {} built-in, {} external", builtInColumns().size(), external.size());
    }

    private static void registerProviderClass(String className, List<ExplorerColumn> external) {
        Class<?> providerClass;
        try {
            providerClass = Class.forName(className, true, ExplorerColumnService.class.getClassLoader());
        } catch (Throwable first) {
            try {
                providerClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            } catch (Throwable second) {
                warnOnce(className, "load explorer column provider class", second);
                return;
            }
        }
        if (!ExplorerColumnProvider.class.isAssignableFrom(providerClass)) {
            LOGGER.warn("Class {} is annotated with @ExplorerColumns but does not implement ExplorerColumnProvider",
                    className);
            return;
        }
        ExplorerColumnProvider provider;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends ExplorerColumnProvider> typed =
                    (Class<? extends ExplorerColumnProvider>) providerClass;
            provider = typed.getDeclaredConstructor().newInstance();
        } catch (Throwable failure) {
            warnOnce(className, "instantiate explorer column provider", failure);
            return;
        }
        ExplorerColumnRegistry registry = new ExplorerColumnRegistry();
        try {
            provider.registerColumns(registry);
        } catch (Throwable failure) {
            // 整批丢弃，避免 provider 抛异常后留下半套列定义。
            warnOnce(className, "run explorer column provider", failure);
            return;
        }
        for (ExplorerColumn column : registry.columns()) {
            if (BY_ID.containsKey(column.id()) || external.stream().anyMatch(existing -> existing.id().equals(column.id()))) {
                LOGGER.warn("Skipping duplicate explorer column id '{}' from {}", column.id(), className);
                continue;
            }
            external.add(column);
        }
    }

    private static void warnOnce(String key, String action, Throwable failure) {
        String marker = key + "|" + action;
        if (REPORTED_FAILURES.add(marker)) {
            LOGGER.warn("Failed to {} ({}); this provider is ignored", action, key, failure);
        }
    }

    /** 全部列：内置列按固定顺序，第三方列按稳定 ID 排序。 */
    public static List<ExplorerColumn> columns() {
        return List.copyOf(COLUMNS);
    }

    /** 表头右键直接显示的常用内置列 ID。 */
    public static List<String> commonColumnIds() {
        return List.of(ExplorerViewState.BUILT_IN_COLUMN_IDS).subList(0, 6);
    }

    public static ExplorerColumn column(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** 给状态补齐当前已注册列的默认宽度/显隐/列序；已有设置保持不变。 */
    public static void ensureStateColumns(ExplorerViewState state) {
        for (ExplorerColumn column : COLUMNS) {
            state.ensureColumn(column.id(), column.defaultWidth(), column.defaultVisible());
        }
        state.normalize();
    }

    /** 第三方便携访问：当前条目的只读上下文。 */
    public static ExplorerColumnContext context(VirtualFileSystem.Entry entry, String displayName,
                                                Supplier<ItemStack> stackSupplier) {
        return new ClientColumnContext(entry, displayName, stackSupplier);
    }

    /** 安全取显示文本；provider 抛异常时返回空串并只记录一次。 */
    public static String value(ExplorerColumn column, ExplorerColumnContext context) {
        if (column == null) {
            return "";
        }
        try {
            String value = column.value(context);
            return value == null ? "" : value;
        } catch (Throwable failure) {
            warnColumn(column, "value", failure);
            return "";
        }
    }

    /** 安全取列标题；provider 抛异常时回退到列 ID。 */
    public static Component label(ExplorerColumn column) {
        if (column == null) {
            return Component.empty();
        }
        try {
            Component label = column.label();
            return label == null ? Component.literal(column.id()) : label;
        } catch (Throwable failure) {
            warnColumn(column, "label", failure);
            return Component.literal(column.id());
        }
    }

    /** 安全比较；provider 异常时视为相等，并只记录一次。 */
    public static int compare(ExplorerColumn column, ExplorerColumnContext left, ExplorerColumnContext right) {
        if (column == null) {
            return 0;
        }
        try {
            if (column.comparator() != null) {
                return column.comparator().compare(left, right);
            }
            Comparable<?> a = column.sortValue(left);
            Comparable<?> b = column.sortValue(right);
            if (a == null && b == null) {
                return String.CASE_INSENSITIVE_ORDER.compare(value(column, left), value(column, right));
            }
            if (a == null) {
                return -1;
            }
            if (b == null) {
                return 1;
            }
            if (a instanceof Number numberA && b instanceof Number numberB) {
                return Double.compare(numberA.doubleValue(), numberB.doubleValue());
            }
            if (a instanceof String stringA && b instanceof String stringB) {
                return String.CASE_INSENSITIVE_ORDER.compare(stringA, stringB);
            }
            if (a.getClass() == b.getClass() && a instanceof Comparable comparable) {
                @SuppressWarnings("unchecked")
                Comparable<Object> typed = comparable;
                return typed.compareTo(b);
            }
            return String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a), String.valueOf(b));
        } catch (Throwable failure) {
            warnColumn(column, "compare", failure);
            return 0;
        }
    }

    private static void warnColumn(ExplorerColumn column, String action, Throwable failure) {
        String marker = column.id() + "|" + action;
        if (REPORTED_FAILURES.add(marker)) {
            LOGGER.warn("Explorer column '{}' failed during {}; cells will fall back safely",
                    column.id(), action, failure);
        }
    }

    private static List<ExplorerColumn> builtInColumns() {
        List<ExplorerColumn> columns = new ArrayList<>();
        columns.add(column("informationera:name", "informationera.fe.name",
                ctx -> ctx.name(),
                (a, b) -> VirtualFileSystem.naturalCompare(a.name(), b.name())));
        columns.add(column("informationera:modified", "informationera.fe.modified",
                ctx -> ctx.modified(),
                ctx -> ctx.modified()));
        columns.add(column("informationera:type", "informationera.fe.type",
                ctx -> ctx.type(),
                ctx -> ctx.type()));
        columns.add(column("informationera:size", "informationera.fe.size",
                ctx -> ctx.folder() ? "" : formatSize(ctx.count()),
                ctx -> ctx.folder() ? -1L : ctx.count()));
        columns.add(column("informationera:created", "informationera.fe.created",
                ctx -> ctx.created(),
                ctx -> ctx.created()));
        columns.add(column("informationera:author", "informationera.fe.author",
                ctx -> ctx.author(),
                ctx -> ctx.author()));
        columns.add(column("informationera:registry_id", "informationera.fe.column_registry_id",
                ctx -> ctx.itemId(),
                ctx -> ctx.itemId()));
        columns.add(column("informationera:namespace", "informationera.fe.column_namespace",
                ctx -> ctx.namespace(),
                ctx -> ctx.namespace()));
        columns.add(column("informationera:max_stack", "informationera.fe.column_max_stack",
                ctx -> positiveIntText(ctx.maxStack()),
                ctx -> ctx.maxStack()));
        columns.add(column("informationera:durability", "informationera.fe.column_durability",
                ctx -> positiveIntText(ctx.maxDamage()),
                ctx -> ctx.maxDamage()));
        return columns;
    }

    private static ExplorerColumn column(String id, String translationKey,
                                         java.util.function.Function<ExplorerColumnContext, String> value,
                                         java.util.function.Function<ExplorerColumnContext, ? extends Comparable<?>> sortValue) {
        int index = indexOfBuiltIn(id);
        return ExplorerColumn.builder(id)
                .label(Component.translatable(translationKey))
                .defaultWidth(index >= 0 ? (int) ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[index] : 120)
                .defaultVisible(index >= 0 && ExplorerViewState.BUILT_IN_COLUMN_VISIBLE[index])
                .value(value)
                .sortValue(sortValue)
                .build();
    }

    private static ExplorerColumn column(String id, String translationKey,
                                         java.util.function.Function<ExplorerColumnContext, String> value,
                                         Comparator<ExplorerColumnContext> comparator) {
        int index = indexOfBuiltIn(id);
        return ExplorerColumn.builder(id)
                .label(Component.translatable(translationKey))
                .defaultWidth(index >= 0 ? (int) ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[index] : 120)
                .defaultVisible(index >= 0 && ExplorerViewState.BUILT_IN_COLUMN_VISIBLE[index])
                .value(value)
                .comparator(comparator)
                .build();
    }

    private static int indexOfBuiltIn(String id) {
        for (int i = 0; i < ExplorerViewState.BUILT_IN_COLUMN_IDS.length; i++) {
            if (ExplorerViewState.BUILT_IN_COLUMN_IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static String positiveIntText(int value) {
        return value > 0 ? Integer.toString(value) : "";
    }

    static String formatSize(long count) {
        if (count < 0L) {
            return "";
        }
        if (count < 10_000L) {
            return Long.toString(count);
        }
        if (count < 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f万", count / 10_000.0);
        }
        return String.format(Locale.ROOT, "%.2f亿", count / 100_000_000.0);
    }

    /** 每个条目的上下文实现；物品栈延迟构造，普通排序/列不触发物品解析。 */
    private static final class ClientColumnContext implements ExplorerColumnContext {
        private final boolean folder;
        private final String name;
        private final String itemId;
        private final String namespace;
        private final String type;
        private final String modified;
        private final String created;
        private final String author;
        private final long count;
        private final Supplier<ItemStack> stackSupplier;
        private ItemStack stack;
        private boolean stackResolved;

        private ClientColumnContext(VirtualFileSystem.Entry entry, String displayName,
                                    Supplier<ItemStack> stackSupplier) {
            this.folder = entry.folder;
            this.name = displayName == null ? "" : displayName;
            this.itemId = entry.itemId;
            this.namespace = namespaceOf(entry.itemId);
            this.type = entry.type;
            this.modified = entry.modified;
            this.created = entry.created;
            this.author = entry.author;
            this.count = entry.count;
            this.stackSupplier = stackSupplier;
        }

        @Override
        public boolean folder() {
            return folder;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public String itemId() {
            return itemId == null ? "" : itemId;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public String type() {
            return type == null ? "" : type;
        }

        @Override
        public String modified() {
            return modified == null ? "" : modified;
        }

        @Override
        public String created() {
            return created == null ? "" : created;
        }

        @Override
        public String author() {
            return author == null ? "" : author;
        }

        @Override
        public ItemStack stack() {
            if (!stackResolved) {
                stackResolved = true;
                try {
                    ItemStack value = stackSupplier == null ? null : stackSupplier.get();
                    stack = value == null ? ItemStack.EMPTY : value;
                } catch (Throwable failure) {
                    stack = ItemStack.EMPTY;
                }
            }
            return stack;
        }

        @Override
        public int maxStack() {
            ItemStack value = stack();
            return value.isEmpty() ? 0 : value.getMaxStackSize();
        }

        @Override
        public int maxDamage() {
            ItemStack value = stack();
            return value.isEmpty() ? 0 : value.getMaxDamage();
        }

        private static String namespaceOf(String itemId) {
            if (itemId == null) {
                return "";
            }
            int colon = itemId.indexOf(':');
            return colon > 0 ? itemId.substring(0, colon) : "";
        }
    }
}
