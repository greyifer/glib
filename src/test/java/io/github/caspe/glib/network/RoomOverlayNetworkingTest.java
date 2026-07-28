package io.github.caspe.glib.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomOverlayNetworkingTest {
	@Test
	void rejectsIngressFloodsBeforeSchedulingServerWork() {
		UUID playerId = UUID.randomUUID();
		RoomOverlayNetworking.clearRefreshAdmission(playerId);
		try {
			for (int request = 0;
					request < RoomOverlayNetworking.MAX_REFRESH_REQUESTS_PER_WINDOW;
					request++) {
				assertTrue(RoomOverlayNetworking.admitRefresh(playerId, 100L));
			}
			assertFalse(RoomOverlayNetworking.admitRefresh(playerId, 100L));
			assertFalse(RoomOverlayNetworking.admitRefresh(
				playerId, 100L + RoomOverlayNetworking.REFRESH_WINDOW_NANOS - 1L));
			assertTrue(RoomOverlayNetworking.admitRefresh(
				playerId, 100L + RoomOverlayNetworking.REFRESH_WINDOW_NANOS));
		} finally {
			RoomOverlayNetworking.clearRefreshAdmission(playerId);
		}
	}
}
