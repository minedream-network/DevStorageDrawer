package com.teenkung.devstoragedrawer.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DrawerBreakTicketTest {
    @Test
    void confirmationCanOnlyBreakTheSameDrawerSnapshotOnce() {
        final UUID owner = UUID.randomUUID();
        final DrawerBreakTicket.BlockKey target = new DrawerBreakTicket.BlockKey(UUID.randomUUID(), 1, 64, 2);
        final DrawerBreakTicket ticket = new DrawerBreakTicket(owner, target, "state-1", 1_000L);

        assertFalse(ticket.consume(owner, target, "state-1", 1_100L));
        assertTrue(ticket.approve(1_100L));
        assertFalse(ticket.consume(UUID.randomUUID(), target, "state-1", 1_200L));
        assertFalse(ticket.consume(owner, new DrawerBreakTicket.BlockKey(target.worldId(), 2, 64, 2), "state-1", 1_200L));
        assertFalse(ticket.consume(owner, target, "state-2", 1_200L));
        assertTrue(ticket.consume(owner, target, "state-1", 1_200L));
        assertFalse(ticket.consume(owner, target, "state-1", 1_200L));
    }

    @Test
    void expiredDialogOrApprovalDoesNotAuthorizeABreak() {
        final UUID owner = UUID.randomUUID();
        final DrawerBreakTicket.BlockKey target = new DrawerBreakTicket.BlockKey(UUID.randomUUID(), 0, 0, 0);
        final DrawerBreakTicket lateDialog = new DrawerBreakTicket(owner, target, "state", 1_000L);
        assertFalse(lateDialog.approve(62_000L));

        final DrawerBreakTicket expiredApproval = new DrawerBreakTicket(owner, target, "state", 1_000L);
        assertTrue(expiredApproval.approve(2_000L));
        assertFalse(expiredApproval.consume(owner, target, "state", 17_001L));
    }
}
