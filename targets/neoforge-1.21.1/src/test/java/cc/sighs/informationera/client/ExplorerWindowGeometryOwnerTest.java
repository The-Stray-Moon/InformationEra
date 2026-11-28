package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口持久化所有权测试：只有最后被玩家调整几何的窗口能改写记录，
 * 关闭没动过的旧窗口不会覆盖它。
 */
public class ExplorerWindowGeometryOwnerTest {

    @Test
    public void onlyLastMarkedWindowOwnsGeometry() {
        ExplorerWindowGeometryOwner owner = new ExplorerWindowGeometryOwner();
        Object first = new Object();
        Object second = new Object();

        owner.mark(first);
        assertTrue(owner.isOwner(first));
        assertFalse(owner.isOwner(second));

        owner.mark(second);
        assertFalse(owner.isOwner(first));
        assertTrue(owner.isOwner(second));
    }

    @Test
    public void closingOwnerClearsOwnership() {
        ExplorerWindowGeometryOwner owner = new ExplorerWindowGeometryOwner();
        Object window = new Object();

        owner.mark(window);
        owner.forget(window);

        assertFalse(owner.isOwner(window));
    }

    @Test
    public void closingAnotherWindowDoesNotClearTheOwner() {
        ExplorerWindowGeometryOwner owner = new ExplorerWindowGeometryOwner();
        Object adjusted = new Object();
        Object untouched = new Object();

        owner.mark(adjusted);
        owner.forget(untouched);

        assertTrue(owner.isOwner(adjusted));
    }

    @Test
    public void nullWindowNeverOwnsGeometry() {
        ExplorerWindowGeometryOwner owner = new ExplorerWindowGeometryOwner();
        assertFalse(owner.isOwner(null));
        owner.mark(new Object());
        assertFalse(owner.isOwner(null));
    }
}
