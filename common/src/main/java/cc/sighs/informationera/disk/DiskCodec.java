package cc.sighs.informationera.disk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 磁盘的行式编解码器。
 *
 * <p>网络传输与磁盘持久化共用同一套格式，因此只需要维护一处解析逻辑。编码结果是若干行；
 * 每行第一个制表符前是记录类型，其余字段用制表符分隔。任何字段内的制表符、换行与反斜杠
 * 都先做转义，因此名称里出现这些字符也不会破坏结构。
 *
 * <pre>
 * D &lt;diskName&gt;
 * Z &lt;retentionDays&gt; &lt;idCounter&gt;
 * F &lt;id&gt; &lt;name&gt; &lt;parentId&gt; &lt;createdAt&gt; &lt;updatedAt&gt;         文件夹
 * P &lt;parentId&gt; &lt;childId&gt;                                      父子关系
 * L &lt;id&gt; &lt;itemId&gt; &lt;nbt&gt; &lt;name&gt; &lt;createdAt&gt; &lt;updatedAt&gt;        文件
 * S &lt;itemId&gt; &lt;nbt&gt; &lt;count&gt;                                    库存
 * R &lt;nodeId&gt; &lt;originParentId&gt; &lt;deletedAt&gt;                     回收站记录
 * </pre>
 *
 * <p>解析对未知行、字段不足的行、越界数值一律宽容跳过，保证旧数据或损坏数据不会让
 * 整个磁盘打不开。
 */
public final class DiskCodec {

    private static final char SEP = '\t';
    private static final char LF = '\n';

    private DiskCodec() {
    }

    // ---------------------------------------------------------------- 编码

    public static String encode(PlayerDisk disk) {
        StringBuilder sb = new StringBuilder();
        write(sb, "D", disk.diskName());
        write(sb, "Z", Integer.toString(disk.recycleRetentionDays()), Long.toString(disk.idCounterRaw()));

        for (DiskFolder folder : disk.allFolders()) {
            write(sb, "F", folder.id(), folder.name(), folder.parentId(),
                    Long.toString(folder.createdAt()), Long.toString(folder.updatedAt()));
        }
        for (DiskFolder folder : disk.allFolders()) {
            for (String child : disk.childrenOf(folder.id())) {
                write(sb, "P", folder.id(), child);
            }
        }
        for (DiskFile file : disk.files().values()) {
            write(sb, "L", file.id(), file.key().itemId(), file.key().nbt(), file.name(),
                    Long.toString(file.createdAt()), Long.toString(file.updatedAt()));
        }
        for (Map.Entry<ItemKey, Long> entry : disk.inventory().entrySet()) {
            write(sb, "S", entry.getKey().itemId(), entry.getKey().nbt(), Long.toString(entry.getValue()));
        }
        disk.recycleRecords(new PlayerDisk.RecycleRecordConsumer() {
            @Override
            public void accept(String nodeId, String originParentId, long deletedAt) {
                write(sb, "R", nodeId, originParentId, Long.toString(deletedAt));
            }
        });
        return sb.toString();
    }

    private static void write(StringBuilder sb, String tag, String... fields) {
        sb.append(tag);
        for (String field : fields) {
            sb.append(SEP).append(escape(field));
        }
        sb.append(LF);
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 't':
                    sb.append('\t');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                default:
                    sb.append(next);
                    break;
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 解码

    /** 从编码文本还原磁盘；空文本返回 null。 */
    public static PlayerDisk decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String diskName = null;
        int retention = 30;
        long idCounter = 0L;
        List<String[]> rows = new ArrayList<>();

        for (String line : data.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = splitLine(line);
            if (parts.length == 0) {
                continue;
            }
            switch (parts[0]) {
                case "D":
                    diskName = parts.length > 1 ? parts[1] : null;
                    break;
                case "Z":
                    retention = parseInt(parts, 1, 30);
                    idCounter = parseLong(parts, 2, 0L);
                    break;
                default:
                    rows.add(parts);
                    break;
            }
        }

        PlayerDisk disk = new PlayerDisk(diskName);
        disk.setRecycleRetentionDaysRaw(retention);
        for (String[] parts : rows) {
            switch (parts[0]) {
                case "F": {
                    if (parts.length < 6) {
                        break;
                    }
                    disk.putFolderRaw(new DiskFolder(parts[1], parts[2], parts[3],
                            parseLong(parts, 4, 0L), parseLong(parts, 5, 0L)));
                    break;
                }
                case "P": {
                    if (parts.length >= 3) {
                        disk.putChildRaw(parts[1], parts[2]);
                    }
                    break;
                }
                case "L": {
                    if (parts.length < 7) {
                        break;
                    }
                    disk.putFileRaw(new DiskFile(parts[1], new ItemKey(parts[2], parts[3]), parts[4],
                            parseLong(parts, 5, 0L), parseLong(parts, 6, 0L)));
                    break;
                }
                case "S": {
                    if (parts.length >= 4) {
                        try {
                            disk.putInventoryRaw(new ItemKey(parts[1], parts[2]), parseLong(parts, 3, 0L));
                        } catch (IllegalArgumentException ignored) {
                            // 物品 id 非法的记录直接丢弃。
                        }
                    }
                    break;
                }
                case "R": {
                    if (parts.length >= 4) {
                        disk.putRecycleRaw(parts[1], parts[2], parseLong(parts, 3, 0L));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        disk.setIdCounterRaw(idCounter);
        return disk;
    }

    private static String[] splitLine(String line) {
        List<String> fields = new ArrayList<>(8);
        int start = 0;
        while (true) {
            int idx = line.indexOf(SEP, start);
            if (idx < 0) {
                fields.add(unescape(line.substring(start)));
                break;
            }
            fields.add(unescape(line.substring(start, idx)));
            start = idx + 1;
        }
        return fields.toArray(new String[0]);
    }

    private static int parseInt(String[] parts, int index, int fallback) {
        long value = parseLong(parts, index, fallback);
        return (int) value;
    }

    private static long parseLong(String[] parts, int index, long fallback) {
        if (index >= parts.length) {
            return fallback;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
