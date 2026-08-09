package hk.flynt.mcms.fabric;

import hk.flynt.mcms.McmsMod;
import net.fabricmc.api.ClientModInitializer;

public class McmsModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        McmsMod.init();
    }
}