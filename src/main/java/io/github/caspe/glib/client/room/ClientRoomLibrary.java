package io.github.caspe.glib.client.room;

import io.github.caspe.glib.network.RoomLibraryRequestPayload;
import io.github.caspe.glib.network.RoomLibrarySyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientRoomLibrary {
	private static volatile List<RoomLibrarySyncPayload.Entry> rooms = List.of();
	private static volatile long revision;
	private static volatile boolean received;
	private static volatile boolean supported;
	private static volatile boolean requestAttempted;
	private static volatile boolean truncated;
	private static volatile int totalRooms;
	private static long pendingRevision = Long.MIN_VALUE;
	private static int pendingPageCount;
	private static final Map<Integer, List<RoomLibrarySyncPayload.Entry>> pendingPages =
		new LinkedHashMap<>();

	private ClientRoomLibrary() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(
			RoomLibrarySyncPayload.ID,
			(payload, context) -> context.client().execute(() -> accept(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			rooms = List.of();
			revision = 0L;
			received = false;
			supported = false;
			requestAttempted = false;
			truncated = false;
			totalRooms = 0;
			pendingRevision = Long.MIN_VALUE;
			pendingPageCount = 0;
			pendingPages.clear();
		});
	}

	public static List<RoomLibrarySyncPayload.Entry> rooms() {
		return rooms;
	}

	public static long revision() {
		return revision;
	}

	public static boolean received() {
		return received;
	}

	public static boolean supported() {
		return supported;
	}

	public static boolean requestAttempted() {
		return requestAttempted;
	}

	public static boolean truncated() {
		return truncated;
	}

	public static int totalRooms() {
		return totalRooms;
	}

	public static boolean requestRefresh() {
		requestAttempted = true;
		if (!ClientPlayNetworking.canSend(RoomLibraryRequestPayload.ID)) {
			supported = false;
			return false;
		}
		supported = true;
		ClientPlayNetworking.send(new RoomLibraryRequestPayload());
		return true;
	}

	private static void accept(RoomLibrarySyncPayload payload) {
		supported = true;
		requestAttempted = true;
		if (payload.revision() != pendingRevision || payload.pageCount() != pendingPageCount) {
			pendingRevision = payload.revision();
			pendingPageCount = payload.pageCount();
			pendingPages.clear();
		}
		pendingPages.put(payload.pageIndex(), payload.rooms());
		if (pendingPages.size() != pendingPageCount) {
			return;
		}
		List<RoomLibrarySyncPayload.Entry> combined = new ArrayList<>();
		for (int page = 0; page < pendingPageCount; page++) {
			List<RoomLibrarySyncPayload.Entry> entries = pendingPages.get(page);
			if (entries == null) {
				return;
			}
			combined.addAll(entries);
		}
		rooms = List.copyOf(combined);
		revision = payload.revision();
		totalRooms = payload.totalRooms();
		truncated = payload.truncated();
		received = true;
	}
}
