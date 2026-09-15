package cc.sighs.informationera.disk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link PlayerDisk} 的核心语义测试。
 *
 * <p>重点覆盖需求里最容易出错的两条：
 * <ol>
 *   <li>同一种物品的多个文件共享同一份数量；复制文件不产生新物品。</li>
 *   <li>最后一个文件被彻底删除时，物品才从库存消失。</li>
 * </ol>
 */
public class PlayerDiskTest {

    private static final ItemKey APPLE = new ItemKey("minecraft:apple", "");
    private static final ItemKey IRON = new ItemKey("minecraft:iron_ingot", "");
    /** 同 id 但带组件：必须与无组件的苹果视为不同物品。 */
    private static final ItemKey ENCHANTED_APPLE = new ItemKey("minecraft:apple", "{Enchantments:[{id:\"minecraft:sharpness\",lvl:1}]}");

    private static PlayerDisk newDisk() {
        return new PlayerDisk("Test Disk");
    }

    // ---------------------------------------------------------------- 基本属性

    @Test
    public void newDiskHasRootPresetFoldersAndRecycleBin() {
        PlayerDisk disk = newDisk();
        assertNotNull(disk.root());
        // 根目录的名字就是玩家名字。
        assertEquals("Test Disk", disk.root().name());
        // 四个默认分类文件夹都必须存在。
        for (String id : DiskFolder.PRESET_IDS) {
            assertNotNull("missing preset folder " + id, disk.folder(id));
            assertEquals(DiskFolder.ROOT_ID, disk.folder(id).parentId());
        }
        assertNotNull(disk.folder(DiskFolder.RECYCLE_ID));
        // 没开窗口时的落点是根目录。
        assertEquals(DiskFolder.ROOT_ID, disk.defaultDropFolderId());
        assertEquals(0, disk.stockKinds());
    }

    @Test
    public void presetFoldersAreRenamableLikeUserFolders() {
        PlayerDisk disk = newDisk();
        assertTrue(disk.rename(DiskFolder.BLOCKS_ID, "我的方块").isOk());
        assertEquals("我的方块", disk.folder(DiskFolder.BLOCKS_ID).name());
    }

    @Test
    public void presetFolderNamesAreRecognisableForLocalisation() {
        assertEquals("Blocks", DiskFolder.presetDefaultName(DiskFolder.BLOCKS_ID));
        assertEquals("informationera.folder.food", DiskFolder.presetTranslationKey(DiskFolder.FOOD_ID));
        assertTrue(DiskFolder.isPreset(DiskFolder.WEAPONS_ID));
        assertFalse(DiskFolder.isPreset("some_random_folder"));
    }

    // ---------------------------------------------------------------- 入库

    @Test
    public void depositCreatesOneFilePerKind() {
        PlayerDisk disk = newDisk();
        int baseline = disk.childrenOf(DiskFolder.ROOT_ID).size();
        assertTrue(disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L).isOk());
        assertEquals(10L, disk.quantityOf(APPLE));
        assertEquals(baseline + 1, disk.childrenOf(DiskFolder.ROOT_ID).size());

        // 再次入库同种物品：数量累加，不新建文件。
        assertTrue(disk.deposit(DiskFolder.ROOT_ID, APPLE, 5L).isOk());
        assertEquals(15L, disk.quantityOf(APPLE));
        assertEquals(baseline + 1, disk.childrenOf(DiskFolder.ROOT_ID).size());
    }

    @Test
    public void depositWithoutFolderRefusesToOrphanStock() {
        PlayerDisk disk = newDisk();
        // 即使显式指向不存在的文件夹，也必须落到有效文件夹，否则物品会没有文件可达。
        disk.deposit("no-such-folder", APPLE, 3L);
        assertEquals(3L, disk.quantityOf(APPLE));
        assertTrue(hasFileForKind(disk, APPLE));
    }

    @Test
    public void depositRejectsNonPositiveAmount() {
        PlayerDisk disk = newDisk();
        assertFalse(disk.deposit(DiskFolder.ROOT_ID, APPLE, 0L).isOk());
        assertFalse(disk.deposit(DiskFolder.ROOT_ID, APPLE, -5L).isOk());
        assertEquals(0L, disk.quantityOf(APPLE));
    }

    @Test
    public void depositTreatsItemWithComponentsAsDistinctKind() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 2L);
        disk.deposit(DiskFolder.ROOT_ID, ENCHANTED_APPLE, 7L);
        assertEquals(2L, disk.quantityOf(APPLE));
        assertEquals(7L, disk.quantityOf(ENCHANTED_APPLE));
        assertEquals(2, disk.stockKinds());
    }

    // ---------------------------------------------------------------- 共享数量（需求核心）

    @Test
    public void copiedFileSharesTheSameQuantity() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);
        String folderId = createFolder(disk, DiskFolder.ROOT_ID, "Basket");

        // 复制到另一个文件夹：多一个文件，但总库存不变。
        assertTrue(disk.transfer(Collections.singletonList(fileId), folderId, true).isOk());
        assertEquals("总量不应因复制而增加", 10L, disk.quantityOf(APPLE));
        assertEquals("同一种物品要有两个文件", 2, countFilesForKind(disk, APPLE));

        // 取出 4 个：两个文件看到的数量都必须变成 6。
        assertEquals(4L, disk.withdraw(APPLE, 4L));
        assertEquals(6L, disk.quantityOf(APPLE));
        assertEquals("复制出来的文件必须同步减少", 6L, disk.quantityOf(APPLE));
    }

    /**
     * 复制到**同一个文件夹**：必须成功并自动生成不重名的新文件。
     *
     * <p>对应 Windows 的「复制后在原文件夹粘贴」，它是直接变出一个副本、不打断操作，
     * 而不是弹窗问「同名了怎么办」。
     */
    @Test
    public void copyingIntoTheSameFolderAutoRenames() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);
        String originalName = disk.file(fileId).name();

        OpResult result = disk.transfer(Collections.singletonList(fileId), DiskFolder.ROOT_ID, true);
        assertTrue("复制到原文件夹应当成功", result.isOk());

        // 两个文件都在根目录，且名字不重复。
        assertEquals(2, countFilesForKind(disk, APPLE));
        List<String> names = new ArrayList<>();
        for (String childId : disk.childrenOf(DiskFolder.ROOT_ID)) {
            if (disk.file(childId) != null) {
                names.add(disk.file(childId).name());
            }
        }
        assertTrue("原文件应保留", names.contains(originalName));
        assertEquals("名字必须互不重复", 2, new HashSet<>(names).size());
        // 副本仍然共享同一份库存。
        assertEquals(10L, disk.quantityOf(APPLE));
    }

    /** 连续复制同一个文件多次，编号必须从 2 起逐个递增。 */
    @Test
    public void repeatedCopyNumbersFromTwoUpwards() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 4L);
        String fileId = firstFileId(disk, APPLE);
        String original = disk.file(fileId).name();

        for (int i = 0; i < 3; i++) {
            assertTrue(disk.transfer(Collections.singletonList(fileId), DiskFolder.ROOT_ID, true).isOk());
        }

        List<String> names = fileNamesIn(disk, DiskFolder.ROOT_ID);
        assertEquals(4, names.size());
        assertTrue(names.contains(original));
        // 编号从 2 起，不是从 1 起。
        assertTrue("应生成 (2): " + names, names.contains(original + " (2)"));
        assertTrue("应生成 (3): " + names, names.contains(original + " (3)"));
        assertTrue("应生成 (4): " + names, names.contains(original + " (4)"));
        assertFalse("不应出现 (1)", names.contains(original + " (1)"));
        assertEquals(4L, disk.quantityOf(APPLE));
    }

    /**
     * 复制一个**已经是副本**的文件：编号要继续递增，不能把后缀一层层摞起来。
     *
     * <p>即复制 {@code apple (2)} 得到 {@code apple (3)}，而不是 {@code apple (2) (2)}。
     * 这是 Windows 资源管理器的行为。
     */
    @Test
    public void copyingACopyDoesNotNestSuffixes() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 2L);
        String fileId = firstFileId(disk, APPLE);
        String original = disk.file(fileId).name();

        // 先造出一个副本 apple (2)。
        assertTrue(disk.transfer(Collections.singletonList(fileId), DiskFolder.ROOT_ID, true).isOk());
        String copyId = fileIdByName(disk, DiskFolder.ROOT_ID, original + " (2)");
        assertNotNull("应先生成 (2) 副本", copyId);

        // 再复制这个副本本身。
        assertTrue(disk.transfer(Collections.singletonList(copyId), DiskFolder.ROOT_ID, true).isOk());

        List<String> names = fileNamesIn(disk, DiskFolder.ROOT_ID);
        assertTrue("应生成 (3): " + names, names.contains(original + " (3)"));
        for (String name : names) {
            assertFalse("不应出现嵌套后缀: " + name, name.contains(") ("));
        }
    }

    /** 复制文件夹时同样按编号递增，不嵌套后缀。 */
    @Test
    public void copyingFoldersNumbersFromTwoUpwards() {
        PlayerDisk disk = newDisk();
        String folder = createFolder(disk, DiskFolder.ROOT_ID, "Pics");

        assertTrue(disk.transfer(Collections.singletonList(folder), DiskFolder.ROOT_ID, true).isOk());
        assertTrue(disk.transfer(Collections.singletonList(folder), DiskFolder.ROOT_ID, true).isOk());

        List<String> names = new ArrayList<>();
        for (String childId : disk.childrenOf(DiskFolder.ROOT_ID)) {
            if (disk.folder(childId) != null) {
                names.add(disk.folder(childId).name());
            }
        }
        assertTrue("应生成 Pics (2): " + names, names.contains("Pics (2)"));
        assertTrue("应生成 Pics (3): " + names, names.contains("Pics (3)"));
        assertFalse("不应出现 Pics (1)", names.contains("Pics (1)"));
    }

    /** 列出某文件夹下所有文件的名字。 */
    private static List<String> fileNamesIn(PlayerDisk disk, String folderId) {
        List<String> names = new ArrayList<>();
        for (String childId : disk.childrenOf(folderId)) {
            if (disk.file(childId) != null) {
                names.add(disk.file(childId).name());
            }
        }
        return names;
    }

    /** 按名字找文件 id，找不到返回 null。 */
    private static String fileIdByName(PlayerDisk disk, String folderId, String name) {
        for (String childId : disk.childrenOf(folderId)) {
            if (disk.file(childId) != null && name.equals(disk.file(childId).name())) {
                return childId;
            }
        }
        return null;
    }

    /** 连续复制同一个文件多次，每次都要拿到不同的名字。 */
    @Test
    public void repeatedCopyKeepsProducingUniqueNames() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 4L);
        String fileId = firstFileId(disk, APPLE);

        for (int i = 0; i < 3; i++) {
            assertTrue(disk.transfer(Collections.singletonList(fileId), DiskFolder.ROOT_ID, true).isOk());
        }
        assertEquals(4, countFilesForKind(disk, APPLE));
        Set<String> names = new HashSet<>();
        for (String childId : disk.childrenOf(DiskFolder.ROOT_ID)) {
            if (disk.file(childId) != null) {
                assertTrue("名字不能重复: " + disk.file(childId).name(), names.add(disk.file(childId).name()));
            }
        }
        assertEquals(4L, disk.quantityOf(APPLE));
    }

    /**
     * 库存被取空时，引用它的文件必须一并消失。
     *
     * <p>文件是库存的别名；留一个数量为 0 的文件在界面上是垃圾，也是之前拖到槽位后
     * 残留「0 个苹果」的根因。
     */
    @Test
    public void withdrawingAllRemovesTheFilesToo() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        assertEquals(1, countFilesForKind(disk, APPLE));

        assertEquals(10L, disk.withdraw(APPLE, 10L));
        assertEquals(0L, disk.quantityOf(APPLE));
        assertEquals("库存取空后不应留下 0 数量的文件", 0, countFilesForKind(disk, APPLE));
        assertFalse(disk.inventory().containsKey(APPLE));
    }

    /** 同一物品的多个别名文件，在库存取空时要一起消失。 */
    @Test
    public void withdrawingAllRemovesEveryAliasFile() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 6L);
        String fileId = firstFileId(disk, APPLE);
        String folder = createFolder(disk, DiskFolder.ROOT_ID, "Basket");
        // 复制出第二个别名。
        assertTrue(disk.transfer(Collections.singletonList(fileId), folder, true).isOk());
        assertEquals(2, countFilesForKind(disk, APPLE));

        assertEquals(6L, disk.withdraw(APPLE, 6L));
        assertEquals(0, countFilesForKind(disk, APPLE));
        assertEquals(0L, disk.quantityOf(APPLE));
    }

    /** 部分取出时文件必须保留，只是显示数量变小。 */
    @Test
    public void partialWithdrawKeepsTheFile() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        assertEquals(4L, disk.withdraw(APPLE, 4L));
        assertEquals(6L, disk.quantityOf(APPLE));
        assertEquals(1, countFilesForKind(disk, APPLE));
    }

    @Test
    public void withdrawNeverExceedsStock() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 3L);
        assertEquals(3L, disk.withdraw(APPLE, 100L));
        assertEquals(0L, disk.quantityOf(APPLE));
        assertEquals(0L, disk.withdraw(APPLE, 1L));
    }

    @Test
    public void moveDoesNotChangeQuantity() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 8L);
        String fileId = firstFileId(disk, APPLE);
        String folderId = createFolder(disk, DiskFolder.ROOT_ID, "Archive");

        assertTrue(disk.transfer(Collections.singletonList(fileId), folderId, false).isOk());
        assertEquals(8L, disk.quantityOf(APPLE));
        assertEquals(1, countFilesForKind(disk, APPLE));
        assertTrue(disk.childrenOf(folderId).contains(fileId));
    }

    // ---------------------------------------------------------------- 删除语义

    @Test
    public void purgingLastFileDropsStock() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        disk.deposit(DiskFolder.ROOT_ID, IRON, 5L);
        String appleFile = firstFileId(disk, APPLE);

        assertTrue(disk.purge(Collections.singletonList(appleFile)).isOk());
        assertEquals("最后一个文件被彻底删除后物品应消失", 0L, disk.quantityOf(APPLE));
        assertFalse(disk.inventory().containsKey(APPLE));
        assertEquals("其它物品不受影响", 5L, disk.quantityOf(IRON));
    }

    @Test
    public void purgingOneOfTwoFilesKeepsStock() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);
        String folderId = createFolder(disk, DiskFolder.ROOT_ID, "Basket");
        disk.transfer(Collections.singletonList(fileId), folderId, true);

        // 删掉其中一个引用，数量必须原样保留，因为另一个文件还指着它。
        assertTrue(disk.purge(Collections.singletonList(fileId)).isOk());
        assertEquals(10L, disk.quantityOf(APPLE));
        assertEquals(1, countFilesForKind(disk, APPLE));
    }

    @Test
    public void recycleBinKeepsStockAndRestores() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);

        assertTrue(disk.toRecycleBin(Collections.singletonList(fileId)).isOk());
        assertEquals("进回收站不该丢物品", 10L, disk.quantityOf(APPLE));
        assertTrue(disk.isInRecycleBin(fileId));

        assertTrue(disk.restoreFromRecycleBin(Collections.singletonList(fileId)).isOk());
        assertEquals(10L, disk.quantityOf(APPLE));
        assertFalse(disk.isInRecycleBin(fileId));
    }

    @Test
    public void emptyingRecycleBinDropsStock() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);
        disk.toRecycleBin(Collections.singletonList(fileId));

        assertTrue(disk.emptyRecycleBin().isOk());
        assertEquals(0L, disk.quantityOf(APPLE));
    }

    /** 彻底删除回收站里的单个条目：节点与它独占的物品数量都要消失。 */
    @Test
    public void purgingSingleRecycledEntryWorks() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        String fileId = firstFileId(disk, APPLE);
        assertTrue(disk.toRecycleBin(Collections.singletonList(fileId)).isOk());
        assertEquals(1, disk.recycleCount());

        OpResult result = disk.purge(Collections.singletonList(fileId));
        assertTrue(result.isOk());
        assertEquals(0, disk.recycleCount());
        assertFalse(disk.exists(fileId));
        assertFalse(disk.isInRecycleBin(fileId));
        assertEquals(0L, disk.quantityOf(APPLE));
    }

    /** 回收站里既有「已删除的文件夹」也有「已删除的文件」，两者都要能彻底删掉。 */
    @Test
    public void purgingRecycledFolderRemovesItsSubtree() {
        PlayerDisk disk = newDisk();
        String folder = createFolder(disk, DiskFolder.ROOT_ID, "Trash");
        disk.deposit(folder, APPLE, 7L);
        assertTrue(disk.toRecycleBin(Collections.singletonList(folder)).isOk());
        assertEquals(1, disk.recycleCount());

        assertTrue(disk.purge(Collections.singletonList(folder)).isOk());
        assertEquals(0, disk.recycleCount());
        assertNull(disk.folder(folder));
        assertEquals(0L, disk.quantityOf(APPLE));
    }

    /** 回收站里还原与彻底删除可以并存：先删一个，另一个仍能还原。 */
    @Test
    public void recycleBinSupportsBothRestoreAndPurge() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 3L);
        disk.deposit(DiskFolder.ROOT_ID, IRON, 5L);
        String appleFile = firstFileId(disk, APPLE);
        String ironFile = firstFileId(disk, IRON);
        disk.toRecycleBin(Arrays.asList(appleFile, ironFile));
        assertEquals(2, disk.recycleCount());

        // 彻底删除苹果，还原铁锭。
        assertTrue(disk.purge(Collections.singletonList(appleFile)).isOk());
        assertTrue(disk.restoreFromRecycleBin(Collections.singletonList(ironFile)).isOk());

        assertEquals(0L, disk.quantityOf(APPLE));
        assertEquals(5L, disk.quantityOf(IRON));
        assertEquals(0, disk.recycleCount());
        assertFalse(disk.isInRecycleBin(ironFile));
    }

    @Test
    public void purgingFolderDropsNestedStock() {
        PlayerDisk disk = newDisk();
        String outer = createFolder(disk, DiskFolder.ROOT_ID, "Outer");
        String inner = createFolder(disk, outer, "Inner");
        disk.deposit(inner, APPLE, 42L);

        assertTrue(disk.purge(Collections.singletonList(outer)).isOk());
        assertEquals(0L, disk.quantityOf(APPLE));
        assertNull(disk.folder(inner));
    }

    // ---------------------------------------------------------------- 文件夹规则

    @Test
    public void folderNamesMustBeUniqueAndMovesAreCycleSafe() {
        PlayerDisk disk = newDisk();
        String a = createFolder(disk, DiskFolder.ROOT_ID, "A");
        String b = createFolder(disk, a, "B");

        assertFalse("同名应被拒绝", disk.createFolder(DiskFolder.ROOT_ID, "A").isOk());
        assertFalse("不能把父文件夹移进自己的子文件夹", disk.transfer(Collections.singletonList(a), b, false).isOk());
        assertTrue(disk.folder(a).parentId().equals(DiskFolder.ROOT_ID));
    }

    @Test
    public void recycleBinCannotBeMovedInto() {
        PlayerDisk disk = newDisk();
        String a = createFolder(disk, DiskFolder.ROOT_ID, "A");
        assertFalse(disk.transfer(Collections.singletonList(a), DiskFolder.RECYCLE_ID, false).isOk());
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    public void snapshotRestoreUndoesEverything() {
        PlayerDisk disk = newDisk();
        disk.deposit(DiskFolder.ROOT_ID, APPLE, 10L);
        PlayerDisk.Snapshot snapshot = disk.snapshot();

        String folder = createFolder(disk, DiskFolder.ROOT_ID, "Temp");
        disk.deposit(folder, IRON, 5L);
        disk.withdraw(APPLE, 4L);
        assertEquals(6L, disk.quantityOf(APPLE));

        disk.restore(snapshot);
        assertEquals(10L, disk.quantityOf(APPLE));
        assertEquals(0L, disk.quantityOf(IRON));
        assertNull(disk.folder(folder));
    }

    // ---------------------------------------------------------------- 编解码

    @Test
    public void codecRoundTripPreservesEverything() {
        PlayerDisk disk = newDisk();
        String folder = createFolder(disk, DiskFolder.ROOT_ID, "矿物");
        disk.deposit(folder, APPLE, 123456789L);
        disk.deposit(DiskFolder.ROOT_ID, ENCHANTED_APPLE, 7L);
        String recycled = firstFileId(disk, ENCHANTED_APPLE);
        disk.toRecycleBin(Collections.singletonList(recycled));

        String encoded = DiskCodec.encode(disk);
        PlayerDisk decoded = DiskCodec.decode(encoded);

        assertNotNull(decoded);
        assertEquals(disk.diskName(), decoded.diskName());
        assertEquals(123456789L, decoded.quantityOf(APPLE));
        assertEquals(7L, decoded.quantityOf(ENCHANTED_APPLE));
        assertEquals(disk.stockKinds(), decoded.stockKinds());
        assertEquals(disk.recycleCount(), decoded.recycleCount());
        assertNotNull(decoded.folder(folder));
        assertEquals("矿物", decoded.folder(folder).name());
    }

    @Test
    public void codecSurvivesNamesWithSeparators() {
        PlayerDisk disk = newDisk();
        String folder = createFolder(disk, DiskFolder.ROOT_ID, "a\tb\nc\\d");
        disk.deposit(folder, APPLE, 1L);

        PlayerDisk decoded = DiskCodec.decode(DiskCodec.encode(disk));
        assertNotNull(decoded);
        assertNotNull(decoded.folder(folder));
        assertEquals(1L, decoded.quantityOf(APPLE));
    }

    @Test
    public void decodeReturnsNullForEmptyInput() {
        assertNull(DiskCodec.decode(""));
        assertNull(DiskCodec.decode(null));
    }

    @Test
    public void decodeToleratesGarbageLines() {
        // 中间那几行是坏数据（未知标签、字段不足、制表符缺失），解析应当跳过它们继续。
        PlayerDisk decoded = DiskCodec.decode("D\tTest\nXX\tjunk\nL\ttoo_few_fields\nS\tminecraft:apple\t\t5\n");
        assertNotNull(decoded);
        assertEquals(5L, decoded.quantityOf(APPLE));
    }

    // ---------------------------------------------------------------- 服务层

    @Test
    public void depositAllBatchesKindsAndWithdrawalsAreClamped() {
        PlayerDisk disk = newDisk();
        List<DiskService.Deposit> deposits = Arrays.asList(
                new DiskService.Deposit(APPLE, 10L),
                new DiskService.Deposit(IRON, 20L),
                new DiskService.Deposit(APPLE, 5L));
        assertTrue(DiskService.depositAll(disk, DiskFolder.FOOD_ID, deposits).isOk());
        assertEquals(15L, disk.quantityOf(APPLE));
        assertEquals(20L, disk.quantityOf(IRON));

        // 预先估算可取出量：同一种物品重复出现时要扣着余额算。
        List<DiskService.Withdraw> requests = Arrays.asList(
                new DiskService.Withdraw(APPLE, 10L),
                new DiskService.Withdraw(APPLE, 10L),
                new DiskService.Withdraw(IRON, 100L));
        long[] clamped = DiskService.clampWithdrawals(disk, requests);
        assertEquals(10L, clamped[0]);
        assertEquals(5L, clamped[1]);
        assertEquals(20L, clamped[2]);
    }

    // ---------------------------------------------------------------- 辅助

    private static String createFolder(PlayerDisk disk, String parent, String name) {
        assertTrue(disk.createFolder(parent, name).isOk());
        List<String> children = disk.childrenOf(parent);
        return children.get(children.size() - 1);
    }

    private static String firstFileId(PlayerDisk disk, ItemKey key) {
        for (DiskFile file : disk.files().values()) {
            if (file.key().equals(key)) {
                return file.id();
            }
        }
        throw new AssertionError("no file for " + key);
    }

    private static int countFilesForKind(PlayerDisk disk, ItemKey key) {
        int n = 0;
        for (DiskFile file : disk.files().values()) {
            if (file.key().equals(key)) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasFileForKind(PlayerDisk disk, ItemKey key) {
        return countFilesForKind(disk, key) > 0;
    }
}
