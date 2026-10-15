package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DrawerDialogTypeFactoryTest {
    @Test
    void usesNoticeOnlyWhenDynamicFilteringLeavesNoPrimaryActions() {
        assertTrue(DrawerDialogTypeFactory.usesNotice(List.of()));
        assertFalse(DrawerDialogTypeFactory.usesNotice(List.of("upgrade")));
    }
}
