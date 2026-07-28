package io.github.caspe.glib;

import io.github.caspe.glib.api.player.GlibFakePlayers;
import io.github.caspe.glib.api.room.GlibRooms;
import io.github.caspe.glib.network.GlibNetworking;
import net.fabricmc.api.ModInitializer;

public final class Glib implements ModInitializer {
	@Override
	public void onInitialize() {
		GlibNetworking.registerPayloads();
		GlibRooms.initialize();
		GlibFakePlayers.registerCommands();
	}
}
