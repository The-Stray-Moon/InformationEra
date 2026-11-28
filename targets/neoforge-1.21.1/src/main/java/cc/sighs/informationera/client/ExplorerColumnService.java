package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumn;
import cc.sighs.informationera.api.client.ExplorerColumnContext;
import cc.sighs.informationera.api.client.ExplorerColumnProvider;
import cc.sighs.informationera.api.client.ExplorerColumns;
import cc.sighs.informationera.api.client.ExplorerColumnRegistry;
import cc.sighs.informationera.api.client.ExplorerGroup;
import cc.sighs.informationera.api.client.ExplorerGroupRule;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
 * 内置列/分组依据 + 第三方注册表，以及所有列取值/排序/标签/分桶的异常隔离入口。
 *
 * <p>只应由客户端启动流程调用 {@link #discover()}；发现使用 {@link ModFileScanData}，
 * 不主动加载服务端类。第三方列/分组 ID 进入 {@link ExplorerViewState} 的持久化键，增删模组
 * 不会改变其它列或分组依据的设置归属。
 */
public final class ExplorerColumnService {

    private static final Logger LOGGER = LoggerFactory.getLogger("informationera/columns");
    private static final Set<String> REPORTED_FAILURES = new HashSet<>();

    private static final List<ExplorerColumn> COLUMNS = new ArrayList<>();
    private static final Map<String, ExplorerColumn> BY_ID = new HashMap<>();
    private static final List<ExplorerGroup> GROUPS = new ArrayList<>();
    private static final Map<String, ExplorerGroup> GROUPS_BY_ID = new HashMap<>();
    private static final Map<String, String> MOD_NAMES = new HashMap<>();
    private static boolean discovered;

    static {
        for (ExplorerColumn column : builtInColumns()) {
            COLUMNS.add(column);
            BY_ID.put(column.id(), column);
        }
        for (ExplorerGroup group : builtInGroups()) {
            GROUPS.add(group);
            GROUPS_BY_ID.put(group.id(), group);
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
        List<ExplorerGroup> externalGroups = new ArrayList<>();
        try {
            for (ModFileScanData scan : ModList.get().getAllScanData()) {
                scan.getAnnotatedBy(ExplorerColumns.class, ElementType.TYPE).forEach(data -> {
                    String className = data.clazz().getClassName();
                    if (!visited.add(className)) {
                        return;
                    }
                    registerProviderClass(className, external, externalGroups);
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
            if (column.groupRule() != null) {
                addExternalGroup(derivedGroup(column), externalGroups);
            }
        }
        externalGroups.sort(Comparator.comparing(ExplorerGroup::id));
        for (ExplorerGroup group : externalGroups) {
            if (GROUPS_BY_ID.containsKey(group.id())) {
                LOGGER.warn("Skipping duplicate explorer group id '{}' from an external mod", group.id());
                continue;
            }
            GROUPS.add(group);
            GROUPS_BY_ID.put(group.id(), group);
        }
        LOGGER.info("Explorer columns ready: {} built-in, {} external; {} groups ({} external)",
                builtInColumns().size(), external.size(), GROUPS.size(), externalGroups.size());
    }

    private static void addExternalGroup(ExplorerGroup group, List<ExplorerGroup> externalGroups) {
        if (group == null) {
            return;
        }
        if (externalGroups.stream().anyMatch(existing -> existing.id().equals(group.id()))) {
            LOGGER.warn("Skipping duplicate explorer group id '{}' from the same scan", group.id());
            return;
        }
        externalGroups.add(group);
    }

    private static void registerProviderClass(String className, List<ExplorerColumn> external,
                                              List<ExplorerGroup> externalGroups) {
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
        for (ExplorerGroup group : registry.groups()) {
            if (GROUPS_BY_ID.containsKey(group.id())) {
                LOGGER.warn("Skipping duplicate explorer group id '{}' from {}", group.id(), className);
                continue;
            }
            addExternalGroup(group, externalGroups);
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

    /** 全部分组依据：内置按固定顺序，第三方列派生的依据与独立依据按稳定 ID 合并。 */
    public static List<ExplorerGroup> groups() {
        return List.copyOf(GROUPS);
    }

    /** 一级菜单只列常用内置依据；完整列表在可搜索的「更多分组依据」弹窗里。 */
    public static List<String> commonGroupIds() {
        return ExplorerViewState.COMMON_GROUP_IDS;
    }

    /**
     * 表头右键使用的分组依据 ID。命名空间列关联「来源模组」，耐久列关联「耐久状态」，
     * 其余列按列 ID 本身分组；没有对应依据时返回列 ID，由调用方判断是否显示菜单项。
     */
    public static String headerGroupId(String columnId) {
        if (ExplorerViewState.NAMESPACE_COLUMN_ID.equals(columnId)) {
            return ExplorerViewState.GROUP_SOURCE_MOD;
        }
        if (ExplorerViewState.DURABILITY_COLUMN_ID.equals(columnId)) {
            return ExplorerViewState.GROUP_DURABILITY;
        }
        return columnId;
    }

    public static ExplorerGroup group(String id) {
        return id == null ? null : GROUPS_BY_ID.get(id);
    }

    /** 安全分桶；依据异常时退回到“未知”桶并只记录一次。 */
    public static ExplorerGroupRule.Bucket bucket(ExplorerGroup group, ExplorerColumnContext context) {
        if (group == null) {
            return unknownBucket();
        }
        try {
            ExplorerGroupRule.Bucket bucket = group.bucket(context);
            if (bucket == null) {
                return unknownBucket();
            }
            return bucket;
        } catch (Throwable failure) {
            warnGroup(group, failure);
            return unknownBucket();
        }
    }

    /** 安全取分组标题；异常回退到依据 ID。 */
    public static Component groupLabel(ExplorerGroup group) {
        if (group == null) {
            return Component.empty();
        }
        try {
            Component label = group.label();
            if (label == null || label.getString().isBlank()) {
                return Component.literal(group.id());
            }
            return label;
        } catch (Throwable failure) {
            warnGroup(group, failure);
            return Component.literal(group.id());
        }
    }

    /** 安全取分组示例；异常时为空串。 */
    public static Component groupExample(ExplorerGroup group) {
        if (group == null) {
            return Component.empty();
        }
        try {
            Component example = group.example();
            return example == null ? Component.empty() : example;
        } catch (Throwable failure) {
            warnGroup(group, failure);
            return Component.empty();
        }
    }

    private static ExplorerGroupRule.Bucket unknownBucket() {
        // 用一个高于普通数字/字母的字符串排序键，让“未知”在混合类型组序里稳定靠后。
        return ExplorerGroupRule.Bucket.of("unknown",
                Component.translatable("informationera.fe.group.unknown"), "\uffff");
    }

    private static ExplorerGroup derivedGroup(ExplorerColumn column) {
        return ExplorerGroup.builder(column.id())
                .label(column::label)
                .category(ExplorerGroup.Category.EXTENSION)
                .rule(column.groupRule())
                .build();
    }

    private static void warnGroup(ExplorerGroup group, Throwable failure) {
        String marker = group.id() + "|group";
        if (REPORTED_FAILURES.add(marker)) {
            LOGGER.warn("Explorer group '{}' failed; buckets fall back to unknown", group.id(), failure);
        }
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
        return new ClientColumnContext(entry, displayName, "", "", stackSupplier);
    }

    /** 带真实父目录 ID/显示路径的上下文，供「所在文件夹」分组使用。 */
    public static ExplorerColumnContext context(VirtualFileSystem.Entry entry, String displayName,
                                                String parentId, String parentPath,
                                                Supplier<ItemStack> stackSupplier) {
        return new ClientColumnContext(entry, displayName, parentId, parentPath, stackSupplier);
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
                .groupRule(builtInGroupRule(id))
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
                .groupRule(builtInGroupRule(id))
                .build();
    }

    private static List<ExplorerGroup> builtInGroups() {
        List<ExplorerGroup> groups = new ArrayList<>();
        for (String id : ExplorerViewState.BUILT_IN_GROUP_IDS) {
            groups.add(ExplorerGroup.builder(id)
                    .label(groupLabelFor(id))
                    .category(groupCategoryFor(id))
                    .rule(builtInGroupRule(id))
                    .build());
        }
        return groups;
    }

    private static Component groupLabelFor(String id) {
        return switch (id) {
            case ExplorerViewState.GROUP_NAME -> t("informationera.fe.name");
            case ExplorerViewState.GROUP_MODIFIED -> t("informationera.fe.modified");
            case ExplorerViewState.GROUP_CREATED -> t("informationera.fe.created");
            case ExplorerViewState.GROUP_TYPE -> t("informationera.fe.type");
            case ExplorerViewState.GROUP_SIZE -> t("informationera.fe.size");
            case ExplorerViewState.GROUP_AUTHOR -> t("informationera.fe.author");
            case ExplorerViewState.GROUP_SOURCE_MOD -> t("informationera.fe.group.source_mod");
            case ExplorerViewState.GROUP_RARITY -> t("informationera.fe.group.rarity");
            case ExplorerViewState.GROUP_DURABILITY -> t("informationera.fe.group.durability");
            case ExplorerViewState.GROUP_MAX_STACK -> t("informationera.fe.group.max_stack");
            case ExplorerViewState.GROUP_ENCHANTMENT -> t("informationera.fe.group.enchantment");
            case ExplorerViewState.GROUP_PARENT_FOLDER -> t("informationera.fe.group.parent_folder");
            default -> Component.literal(id);
        };
    }

    private static ExplorerGroup.Category groupCategoryFor(String id) {
        return switch (id) {
            case ExplorerViewState.GROUP_PARENT_FOLDER -> ExplorerGroup.Category.LOCATION;
            case ExplorerViewState.GROUP_SOURCE_MOD, ExplorerViewState.GROUP_RARITY,
                 ExplorerViewState.GROUP_DURABILITY, ExplorerViewState.GROUP_MAX_STACK,
                 ExplorerViewState.GROUP_ENCHANTMENT -> ExplorerGroup.Category.ITEM;
            default -> ExplorerGroup.Category.GENERAL;
        };
    }

    private static ExplorerGroupRule builtInGroupRule(String id) {
        if (id == null) {
            return null;
        }
        return switch (id) {
            case ExplorerViewState.GROUP_NAME -> ExplorerGroupRule.builder()
                    .bucket(ctx -> nameBucket(ctx.name()))
                    .example(t("informationera.fe.group.example.name"))
                    .build();
            case ExplorerViewState.GROUP_MODIFIED -> ExplorerGroupRule.builder()
                    .bucket(ctx -> monthBucket(ctx.modified()))
                    .example(t("informationera.fe.group.example.modified"))
                    .build();
            case ExplorerViewState.GROUP_CREATED -> ExplorerGroupRule.builder()
                    .bucket(ctx -> monthBucket(ctx.created()))
                    .example(t("informationera.fe.group.example.created"))
                    .build();
            case ExplorerViewState.GROUP_TYPE -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::typeBucket)
                    .example(t("informationera.fe.group.example.type"))
                    .build();
            case ExplorerViewState.GROUP_SIZE -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::sizeBucket)
                    .example(t("informationera.fe.group.example.size"))
                    .build();
            case ExplorerViewState.GROUP_AUTHOR -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::authorBucket)
                    .example(t("informationera.fe.group.example.author"))
                    .build();
            case ExplorerViewState.GROUP_SOURCE_MOD -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::sourceModBucket)
                    .example(t("informationera.fe.group.example.source_mod"))
                    .build();
            case ExplorerViewState.GROUP_RARITY -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::rarityBucket)
                    .example(t("informationera.fe.group.example.rarity"))
                    .build();
            case ExplorerViewState.GROUP_DURABILITY -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::durabilityBucket)
                    .example(t("informationera.fe.group.example.durability"))
                    .build();
            case ExplorerViewState.GROUP_MAX_STACK -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::stackBucket)
                    .example(t("informationera.fe.group.example.max_stack"))
                    .build();
            case ExplorerViewState.GROUP_ENCHANTMENT -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::enchantmentBucket)
                    .example(t("informationera.fe.group.example.enchantment"))
                    .build();
            case ExplorerViewState.GROUP_PARENT_FOLDER -> ExplorerGroupRule.builder()
                    .bucket(ExplorerColumnService::parentFolderBucket)
                    .example(t("informationera.fe.group.example.parent_folder"))
                    .build();
            default -> null;
        };
    }

    private static ExplorerGroupRule.Bucket nameBucket(String name) {
        if (name == null || name.isEmpty()) {
            return ExplorerGroupRule.Bucket.of("empty", t("informationera.fe.group.unknown"), "");
        }
        String label = String.valueOf(firstLetter(name.charAt(0)));
        return ExplorerGroupRule.Bucket.of("letter:" + label, Component.literal(label), label);
    }

    private static ExplorerGroupRule.Bucket monthBucket(String value) {
        if (value == null || value.isBlank()) {
            return unknownBucket();
        }
        int firstSlash = value.indexOf('/');
        int secondSlash = value.indexOf('/', firstSlash + 1);
        if (firstSlash < 0 || secondSlash < 0) {
            return ExplorerGroupRule.Bucket.of("value:" + value, Component.literal(value), value);
        }
        try {
            int year = Integer.parseInt(value.substring(0, firstSlash).trim());
            int month = Integer.parseInt(value.substring(firstSlash + 1, secondSlash).trim());
            return ExplorerGroupRule.Bucket.of("month:" + year + "-" + month,
                    t("informationera.fe.year_month", year, month), year * 12 + month);
        } catch (NumberFormatException malformed) {
            return ExplorerGroupRule.Bucket.of("value:" + value, Component.literal(value), value);
        }
    }

    private static ExplorerGroupRule.Bucket typeBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return ExplorerGroupRule.Bucket.of("folder", t("informationera.fe.folder"), 0);
        }
        String type = ctx.type();
        if (type == null || type.isBlank()) {
            return unknownBucket();
        }
        // 类型文本会随语言变化；桶 ID 用稳定的 item，标题才用本地化文本。
        return ExplorerGroupRule.Bucket.of("item", Component.literal(type), 1);
    }

    private static ExplorerGroupRule.Bucket sizeBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        long size = ctx.count();
        String id;
        String key;
        int sort;
        if (size < 1024L) {
            id = "small";
            key = "informationera.fe.size_small";
            sort = 0;
        } else if (size < 1024L * 1024L) {
            id = "medium";
            key = "informationera.fe.size_medium";
            sort = 1;
        } else if (size < 1024L * 1024L * 1024L) {
            id = "large";
            key = "informationera.fe.size_large";
            sort = 2;
        } else {
            id = "huge";
            key = "informationera.fe.size_huge";
            sort = 3;
        }
        return ExplorerGroupRule.Bucket.of(id, t(key), sort);
    }

    private static ExplorerGroupRule.Bucket authorBucket(ExplorerColumnContext ctx) {
        String author = ctx.author();
        if (author == null || author.isBlank()) {
            return unknownBucket();
        }
        return ExplorerGroupRule.Bucket.of("author:" + author, Component.literal(author), author);
    }

    private static ExplorerGroupRule.Bucket sourceModBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        String namespace = ctx.namespace();
        if (namespace == null || namespace.isBlank()) {
            return ExplorerGroupRule.Bucket.of("unknown_source",
                    t("informationera.fe.group.unknown_source"), "\uffff");
        }
        String display = modDisplayName(namespace);
        return ExplorerGroupRule.Bucket.of(namespace, Component.literal(display), display);
    }

    private static String modDisplayName(String namespace) {
        String cached = MOD_NAMES.get(namespace);
        if (cached != null) {
            return cached;
        }
        String display = namespace;
        try {
            var container = ModList.get().getModContainerById(namespace).orElse(null);
            if (container != null && container.getModInfo().getDisplayName() != null
                    && !container.getModInfo().getDisplayName().isBlank()) {
                display = container.getModInfo().getDisplayName();
            } else if ("minecraft".equals(namespace)) {
                display = "Minecraft";
            }
        } catch (Throwable failure) {
            // 模组列表尚未就绪时退回命名空间，不缓存，下一次渲染再试。
            return namespace;
        }
        MOD_NAMES.put(namespace, display);
        return display;
    }

    private static ExplorerGroupRule.Bucket rarityBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        ItemStack stack = ctx.stack();
        if (stack.isEmpty()) {
            return unknownBucket();
        }
        Rarity rarity = stack.getRarity();
        String id = "rarity:" + rarity.name().toLowerCase(Locale.ROOT);
        return ExplorerGroupRule.Bucket.of(id, rarityLabel(rarity), rarity.ordinal());
    }

    private static Component rarityLabel(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> t("informationera.fe.group.rarity.common");
            case UNCOMMON -> t("informationera.fe.group.rarity.uncommon");
            case RARE -> t("informationera.fe.group.rarity.rare");
            case EPIC -> t("informationera.fe.group.rarity.epic");
        };
    }

    private static ExplorerGroupRule.Bucket durabilityBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        ItemStack stack = ctx.stack();
        if (stack.isEmpty()) {
            return unknownBucket();
        }
        String id = ExplorerGrouping.durabilityBucketId(ctx.maxDamage(), stack.getDamageValue());
        String key = switch (id) {
            case "repair" -> "informationera.fe.group.durability.repair";
            case "worn" -> "informationera.fe.group.durability.worn";
            case "full" -> "informationera.fe.group.durability.full";
            default -> "informationera.fe.group.durability.none";
        };
        int sort = switch (id) {
            case "repair" -> 0;
            case "worn" -> 1;
            case "full" -> 2;
            default -> 3;
        };
        return ExplorerGroupRule.Bucket.of(id, t(key), sort);
    }

    private static ExplorerGroupRule.Bucket stackBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        int maxStack = ctx.maxStack();
        String id = ExplorerGrouping.stackBucketId(maxStack);
        if (maxStack <= 0) {
            return unknownBucket();
        }
        Component label = maxStack == 1
                ? t("informationera.fe.group.stack.single")
                : Component.literal(Integer.toString(maxStack));
        return ExplorerGroupRule.Bucket.of(id, label, maxStack);
    }

    private static ExplorerGroupRule.Bucket enchantmentBucket(ExplorerColumnContext ctx) {
        if (ctx.folder()) {
            return folderBucket();
        }
        ItemStack stack = ctx.stack();
        boolean stored = false;
        boolean enchanted = false;
        if (!stack.isEmpty()) {
            ItemEnchantments storedEnchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
            ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
            stored = storedEnchantments != null && !storedEnchantments.isEmpty();
            enchanted = enchantments != null && !enchantments.isEmpty();
        }
        String id = ExplorerGrouping.enchantmentBucketId(stored, enchanted);
        String key = switch (id) {
            case "enchanted" -> "informationera.fe.group.enchantment.enchanted";
            case "stored" -> "informationera.fe.group.enchantment.stored";
            default -> "informationera.fe.group.enchantment.none";
        };
        int sort = switch (id) {
            case "enchanted" -> 0;
            case "stored" -> 1;
            default -> 2;
        };
        return ExplorerGroupRule.Bucket.of(id, t(key), sort);
    }

    private static ExplorerGroupRule.Bucket parentFolderBucket(ExplorerColumnContext ctx) {
        String parentId = ctx.parentId();
        if (parentId == null || parentId.isBlank()) {
            return unknownBucket();
        }
        String path = ctx.parentPath();
        String label = path == null || path.isBlank() ? parentId : path;
        return ExplorerGroupRule.Bucket.of(parentId, Component.literal(label), label);
    }

    private static ExplorerGroupRule.Bucket folderBucket() {
        return ExplorerGroupRule.Bucket.of("folder", t("informationera.fe.folder"), -1);
    }

    private static Component t(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /** 名称分组的首字母：中文按 GB2312 区位推拼音首字母，其它字符取大写首字母。 */
    private static char firstLetter(char ch) {
        if (ch >= 0x4e00 && ch <= 0x9fa5) {
            try {
                byte[] gbk = String.valueOf(ch).getBytes("GBK");
                if (gbk.length == 2) {
                    int code = ((gbk[0] & 0xff) << 8) | (gbk[1] & 0xff);
                    if (code >= 0xB0A1 && code < 0xB0C5) return 'A';
                    if (code < 0xB2C1) return 'B';
                    if (code < 0xB4EE) return 'C';
                    if (code < 0xB6EA) return 'D';
                    if (code < 0xB7A2) return 'E';
                    if (code < 0xB8C1) return 'F';
                    if (code < 0xB9FE) return 'G';
                    if (code < 0xBBF7) return 'H';
                    if (code < 0xBFA6) return 'J';
                    if (code < 0xC0AC) return 'K';
                    if (code < 0xC2E8) return 'L';
                    if (code < 0xC4C3) return 'M';
                    if (code < 0xC5B6) return 'N';
                    if (code < 0xC5BE) return 'O';
                    if (code < 0xC6DA) return 'P';
                    if (code < 0xC8BB) return 'Q';
                    if (code < 0xC8F6) return 'R';
                    if (code < 0xCBFA) return 'S';
                    if (code < 0xCDDA) return 'T';
                    if (code < 0xCEF4) return 'W';
                    if (code < 0xD1B9) return 'X';
                    if (code < 0xD4D1) return 'Y';
                    if (code < 0xD7FA) return 'Z';
                }
            } catch (Exception ignored) {
            }
            return ch;
        }
        return Character.toUpperCase(ch);
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
        private final String parentId;
        private final String parentPath;
        private final Supplier<ItemStack> stackSupplier;
        private ItemStack stack;
        private boolean stackResolved;

        private ClientColumnContext(VirtualFileSystem.Entry entry, String displayName,
                                    String parentId, String parentPath,
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
            this.parentId = parentId == null ? "" : parentId;
            this.parentPath = parentPath == null ? "" : parentPath;
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
        public String parentId() {
            return parentId;
        }

        @Override
        public String parentPath() {
            return parentPath;
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
