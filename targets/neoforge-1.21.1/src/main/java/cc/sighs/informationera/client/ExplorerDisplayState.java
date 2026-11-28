package cc.sighs.informationera.client;

import java.util.Locale;
import java.util.Objects;

/**
 * 全局显示习惯：窗格显隐与宽度、紧凑视图、复选框、文件扩展名。
 *
 * <p>这些设置对所有文件夹生效，但按身份隔离；每个玩家、每个服务器各有一份。
 * 纯数据类，不依赖界面与配置，便于独立测试。
 */
public final class ExplorerDisplayState {

    /** 导航窗格默认宽度。 */
    public static final double BUILT_IN_NAV_WIDTH = 240.0;
    /** 预览窗格默认宽度。 */
    public static final double BUILT_IN_PREVIEW_WIDTH = 240.0;
    private static final double MIN_PANE_WIDTH = 120.0;
    private static final double MAX_PANE_WIDTH = 2000.0;
    private static final char FIELD_SEP = '|';

    public boolean showNav;
    public boolean showPreview;
    public boolean showDetails;
    public double navWidth;
    public double previewWidth;
    public boolean compact;
    public boolean checkboxes;
    public boolean showExtensions;

    public ExplorerDisplayState() {
        this.showNav = true;
        this.showPreview = false;
        this.showDetails = false;
        this.navWidth = BUILT_IN_NAV_WIDTH;
        this.previewWidth = BUILT_IN_PREVIEW_WIDTH;
        this.compact = false;
        this.checkboxes = false;
        this.showExtensions = true;
    }

    public static ExplorerDisplayState defaults() {
        return new ExplorerDisplayState();
    }

    public ExplorerDisplayState copy() {
        ExplorerDisplayState copy = new ExplorerDisplayState();
        copy.showNav = showNav;
        copy.showPreview = showPreview;
        copy.showDetails = showDetails;
        copy.navWidth = navWidth;
        copy.previewWidth = previewWidth;
        copy.compact = compact;
        copy.checkboxes = checkboxes;
        copy.showExtensions = showExtensions;
        return copy;
    }

    /** 收敛窗格宽度，避免手工编辑的配置把界面挤没。 */
    public void normalize() {
        navWidth = clampWidth(navWidth, BUILT_IN_NAV_WIDTH);
        previewWidth = clampWidth(previewWidth, BUILT_IN_PREVIEW_WIDTH);
    }

    private static double clampWidth(double width, double fallback) {
        if (!Double.isFinite(width)) {
            return fallback;
        }
        return Math.max(MIN_PANE_WIDTH, Math.min(MAX_PANE_WIDTH, width));
    }

    /** 序列化成一行：导航|预览|详细信息|导航宽|预览宽|紧凑|复选框|扩展名。 */
    public String encode() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(showNav ? '1' : '0').append(FIELD_SEP)
          .append(showPreview ? '1' : '0').append(FIELD_SEP)
          .append(showDetails ? '1' : '0').append(FIELD_SEP)
          .append(String.format(Locale.ROOT, "%.0f", navWidth)).append(FIELD_SEP)
          .append(String.format(Locale.ROOT, "%.0f", previewWidth)).append(FIELD_SEP)
          .append(compact ? '1' : '0').append(FIELD_SEP)
          .append(checkboxes ? '1' : '0').append(FIELD_SEP)
          .append(showExtensions ? '1' : '0');
        return sb.toString();
    }

    /** 解析 {@link #encode()} 的行；格式不完整时退回内置默认值。 */
    public static ExplorerDisplayState decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String[] parts = raw.split("\\" + FIELD_SEP, -1);
        if (parts.length < 8) {
            return defaults();
        }
        ExplorerDisplayState state = defaults();
        state.showNav = truthy(parts[0]);
        state.showPreview = truthy(parts[1]);
        state.showDetails = truthy(parts[2]);
        state.navWidth = parseWidth(parts[3], BUILT_IN_NAV_WIDTH);
        state.previewWidth = parseWidth(parts[4], BUILT_IN_PREVIEW_WIDTH);
        state.compact = truthy(parts[5]);
        state.checkboxes = truthy(parts[6]);
        state.showExtensions = truthy(parts[7]);
        state.normalize();
        return state;
    }

    private static boolean truthy(String raw) {
        return !"0".equals(raw.trim());
    }

    private static double parseWidth(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExplorerDisplayState state)) {
            return false;
        }
        return showNav == state.showNav
                && showPreview == state.showPreview
                && showDetails == state.showDetails
                && Double.compare(navWidth, state.navWidth) == 0
                && Double.compare(previewWidth, state.previewWidth) == 0
                && compact == state.compact
                && checkboxes == state.checkboxes
                && showExtensions == state.showExtensions;
    }

    @Override
    public int hashCode() {
        return Objects.hash(showNav, showPreview, showDetails, navWidth, previewWidth,
                compact, checkboxes, showExtensions);
    }

    @Override
    public String toString() {
        return encode();
    }
}
