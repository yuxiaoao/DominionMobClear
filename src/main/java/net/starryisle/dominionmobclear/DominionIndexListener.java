package net.starryisle.dominionmobclear;

import cn.lunadeer.dominion.events.dominion.DominionCreateEvent;
import cn.lunadeer.dominion.events.dominion.DominionDeleteEvent;
import cn.lunadeer.dominion.events.dominion.modify.DominionReSizeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Marks the claim -> chunk index stale when a claim changes shape. */
public final class DominionIndexListener implements Listener {

    private final MobClearService service;

    public DominionIndexListener(MobClearService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDominionCreate(DominionCreateEvent event) {
        service.markIndexDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDominionDelete(DominionDeleteEvent event) {
        service.markIndexDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDominionResize(DominionReSizeEvent event) {
        service.markIndexDirty();
    }
}
