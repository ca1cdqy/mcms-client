package hk.flynt.mcms.neoforge;

import hk.flynt.mcms.McmsMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(McmsMod.MOD_ID)
public final class McmsModNeoForge {
    public McmsModNeoForge(IEventBus modBus) {
        modBus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        McmsMod.init();
    }
}