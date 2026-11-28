package cc.sighs.informationera.client;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 资源管理器文件名搜索：原名称、通配符始终本地处理；安装 Just Enough Characters 后，
 * 非通配查询额外委托其公开入口
 * {@code me.towdium.jecharacters.utils.Match.contains(CharSequence, CharSequence, boolean)}。
 *
 * <p>JECh 的入口不支持 {@code *} / {@code ?}，因此通配查询仍只按原名称匹配。
 * 未安装 JECh、类或方法缺失、调用异常时都会退回本地原名子串匹配，搜索框本身不受影响。
 *
 * <p>上游建议以 compileOnly 引入后直接调用该方法（JECh issue #201）；这里不把可选模组
 * 加入构建依赖，改用类存在性检测 + 反射调用，保证未安装时仍可构建、可运行。
 */
final class ExplorerSearch {

    private static final String JECH_MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    /** JECh 4.5.30（1.21.1 NeoForge）中核实的公开静态入口；未安装时为 null。 */
    private static final Method JECH_CONTAINS = findJechContains();
    private static volatile boolean jechBroken;

    private ExplorerSearch() {
    }

    static boolean matches(String name, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return true;
        }
        if (matchesPlain(name, query)) {
            return true;
        }
        // 通配符只按本地原名处理；JECh 入口不支持 * / ?。
        if (hasWildcard(query)) {
            return false;
        }
        String text = name == null ? "" : name;
        if (JECH_CONTAINS != null && !jechBroken) {
            try {
                // 拼音查询统一用小写交给 JECh；本地原名路径已做过大小写不敏感匹配。
                String jechQuery = query.toLowerCase(Locale.ROOT);
                if (Boolean.TRUE.equals(JECH_CONTAINS.invoke(null, text, jechQuery, Boolean.TRUE))) {
                    return true;
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                // 记录一次失败后不再重复尝试，避免每次按键都抛异常。
                jechBroken = true;
            }
        }
        return false;
    }

    /**
     * 只做本地原名/通配符匹配，不调用 JECh。
     *
     * <p>标签和模组 id 必须保持英文标识符语义，不能因为安装了 JECh 就让拼音查询
     * 误命中 id；创造标签页才使用带 JECh 的 {@link #matches} 匹配本地化名称。
     */
    static boolean matchesPlain(String name, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return true;
        }
        String text = name == null ? "" : name;
        String lowerName = text.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (hasWildcard(query)) {
            return wildcardMatch(lowerName, lowerQuery);
        }
        return lowerName.contains(lowerQuery);
    }

    private static Method findJechContains() {
        try {
            ClassLoader own = ExplorerSearch.class.getClassLoader();
            Class<?> match;
            try {
                match = Class.forName(JECH_MATCH_CLASS, false, own);
            } catch (ClassNotFoundException missingFromOwn) {
                // 某些加载器会把模组类挂在 context classloader 上，再试一次。
                ClassLoader context = Thread.currentThread().getContextClassLoader();
                if (context == null || context == own) {
                    throw missingFromOwn;
                }
                match = Class.forName(JECH_MATCH_CLASS, false, context);
            }
            return match.getMethod("contains", CharSequence.class, CharSequence.class, boolean.class);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static boolean hasWildcard(String query) {
        return query.indexOf('*') >= 0 || query.indexOf('?') >= 0;
    }

    /** 通配符匹配：{@code *} 任意串，{@code ?} 单字符，大小写不敏感。 */
    private static boolean wildcardMatch(String text, String pattern) {
        int ti = 0;
        int pi = 0;
        int star = -1;
        int mark = -1;
        while (ti < text.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == '?' || pattern.charAt(pi) == text.charAt(ti))) {
                ti++;
                pi++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                star = pi;
                mark = ti;
                pi++;
            } else if (star != -1) {
                pi = star + 1;
                mark++;
                ti = mark;
            } else {
                return false;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }
}
